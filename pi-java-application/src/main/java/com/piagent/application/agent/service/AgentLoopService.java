package com.piagent.application.agent.service;

import com.piagent.application.agent.command.SendMessageCommand;
import com.piagent.application.agent.readmodel.RunHandle;
import com.piagent.domain.agent.AgentRun;
import com.piagent.domain.agent.RunId;
import com.piagent.domain.agent.RunStopReason;
import com.piagent.domain.model.ModelProfile;
import com.piagent.domain.model.llm.LlmResponse;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.SessionStatus;
import com.piagent.domain.session.model.TokenUsage;
import com.piagent.domain.session.model.message.AssistantMessage;
import com.piagent.domain.session.model.message.AssistantStopReason;
import com.piagent.domain.session.model.message.ToolCallBlock;
import com.piagent.domain.session.port.SessionRepository;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Agent Loop 用例服务。
 *
 * <p>职责边界：只推进 run 状态机与终止判定；流式读取、工具执行、上下文投影、上下文压缩
 * 分别委托给 {@link TurnPipeline}、{@link RunRuntime} 与 {@link RunCompactionStep}。
 * LLM 与工具调用期间不持有数据库事务，每次会话追加都是独立短事务。</p>
 *
 * <p>终止不变量：</p>
 * <ul>
 *   <li>只要 assistant 消息里出现 toolCall，就必须先为每个调用写 toolResult，再进入下一轮；</li>
 *   <li>取消、超预算与模型错误都走同一个 {@code finish} 收尾，保证 RUN_SUMMARY 一定写入；</li>
 *   <li>run 结束后会话恢复 IDLE，事件通道保留供 SSE 重放。</li>
 * </ul>
 *
 * <p>M7 新增：每次模型请求前尝试一次自动压缩（每个 run 最多一次）；provider 返回上下文
 * 溢出时强制压缩后重试一次，仍失败则按模型错误结束 run。</p>
 */
@Service
public class AgentLoopService {

    private static final System.Logger LOG = System.getLogger(AgentLoopService.class.getName());
    private static final int MAX_ERROR_LENGTH = 4_000;

    private final SessionRepository sessions;
    private final ActiveModelResolver models;
    private final RunRuntime runtime;
    private final TurnPipeline pipeline;
    private final RunDispatcher dispatcher;
    private final RunCompactionStep compactionStep;
    private final TurnStreamer streamer;
    private final SkillInvocationResolver skills;
    private final RunSteps steps;

    /**
     * 创建 Agent Loop 服务。
     *
     * @param sessions 会话仓储
     * @param models 运行期模型解析器
     * @param runtime 运行运行时
     * @param pipeline turn 流水线
     * @param dispatcher 调度器
     * @param compactionStep 上下文压缩步骤
     */
    public AgentLoopService(SessionRepository sessions, ActiveModelResolver models,
            RunRuntime runtime, TurnPipeline pipeline, RunDispatcher dispatcher,
            RunCompactionStep compactionStep) {
        this.sessions = sessions;
        this.models = models;
        this.runtime = runtime;
        this.pipeline = pipeline;
        this.dispatcher = dispatcher;
        this.compactionStep = compactionStep;
        this.streamer = new TurnStreamer(pipeline, compactionStep, runtime);
        this.skills = new SkillInvocationResolver(pipeline);
        this.steps = new RunSteps(runtime, pipeline);
    }

    /**
     * 启动一次 run。
     *
     * @param command 发送消息命令
     * @return 运行句柄
     */
    public RunHandle start(SendMessageCommand command) {
        ChatSession session = recoverStaleSession(requireSession(command.sessionId()));
        if (!dispatcher.tryAcquire()) {
            throw new DomainException(DomainErrorCode.AGENT_BUSY,
                    "并发运行数已达上限，请稍后重试");
        }
        ReservedRun reserved = null;
        boolean submitted = false;
        try {
            reserved = runtime.reserve(session.identity().id());
            SkillInvocationResolver.Resolved resolved = skills.resolve(command.text());
            runtime.appendUserAndStart(session.identity().id(), resolved.text());
            ModelProfile profile = models.resolve(session.config().modelProfileId(),
                    session.config().thinkingLevel(), !reserved.tools().descriptors().isEmpty());
            RunContext context = new RunContext(session.identity().id(), profile, reserved,
                    compactionStep.budgetOf(profile), resolved.skill());
            ReservedRun handle = reserved;
            dispatcher.dispatch(() -> runSafely(context));
            submitted = true;
            return new RunHandle(handle.runId(), session.identity().id());
        } catch (RuntimeException error) {
            cleanupFailedStart(reserved, session.identity().id());
            throw error;
        } finally {
            if (!submitted) {
                dispatcher.release();
            }
        }
    }

    /**
     * 请求取消会话当前 run。
     *
     * @param sessionId 会话标识
     * @return 被取消的运行句柄
     */
    public RunHandle abort(SessionId sessionId) {
        AgentRun run = runtime.findActiveBySession(sessionId)
                .orElseThrow(() -> new DomainException(DomainErrorCode.RUN_NOT_FOUND,
                        "会话没有活跃 run: " + sessionId));
        runtime.cancel(run.id());
        return new RunHandle(run.id(), sessionId);
    }

    /**
     * 查询会话当前活跃 run。
     *
     * @param sessionId 会话标识
     * @return 活跃运行句柄；没有时为 empty
     */
    public Optional<RunHandle> findActive(SessionId sessionId) {
        return runtime.findActiveBySession(sessionId)
                .map(run -> new RunHandle(run.id(), run.sessionId()));
    }

    /**
     * 查询 run 快照。
     *
     * @param runId 运行标识
     * @return 快照
     */
    public Optional<AgentRun> findRun(RunId runId) {
        return runtime.find(runId);
    }

    /**
     * 等待 run 结束，供测试与 CLI 使用。
     *
     * @param runId 运行标识
     * @param timeout 最长等待时间
     * @return 终态快照
     */
    public AgentRun awaitTerminal(RunId runId, Duration timeout) {
        return runtime.awaitTerminal(runId, timeout);
    }

    private void runSafely(RunContext context) {
        try {
            AgentRun run = runtime.current(context.reserved().runId());
            context.events().runStarted(run, context.profile());
            loop(context);
        } catch (RuntimeException error) {
            try {
                runtime.finish(context.reserved(), RunStopReason.ERROR, message(error));
            } catch (RuntimeException fatal) {
                LOG.log(System.Logger.Level.ERROR,
                        "结束 run 失败: " + context.reserved().runId(), fatal);
            }
        }
    }

    private void loop(RunContext context) {
        RunId runId = context.reserved().runId();
        while (true) {
            AgentRun run = runtime.current(runId);
            if (steps.stopByGuard(context, run)) {
                return;
            }
            ChatSession session = requireSession(context.sessionId());
            compactionStep.tryAutoCompact(session, runId, context.profile(), context.budget(),
                    context.events());
            TurnResult result = streamTurn(context, session, run.turns() + 1);
            if (result == null) {
                return;
            }
            TokenUsage usage = appendAssistant(context.sessionId(), context.profile(), result,
                    context.events());
            runtime.recordTurn(runId, usage);
            if (result.cancelled()) {
                runtime.finish(context.reserved(), RunStopReason.CANCELLED, null);
                return;
            }
            LlmResponse response = result.response();
            if (response.stopReason() == AssistantStopReason.ERROR) {
                runtime.finish(context.reserved(), RunStopReason.ERROR, response.errorMessage());
                return;
            }
            List<ToolCallBlock> calls = toolCalls(response);
            if (calls.isEmpty()) {
                RunStopReason reason = response.stopReason() == AssistantStopReason.LENGTH
                        ? RunStopReason.LENGTH : RunStopReason.STOP;
                runtime.finish(context.reserved(), reason, null);
                return;
            }
            if (!steps.executeTools(context, session, calls)) {
                return;
            }
        }
    }

    /**
     * 执行一次流式模型调用；遇到上下文溢出时压缩一次后重试。
     *
     * @return 流式结果；无法继续时返回 null（run 已收尾）
     */
    private TurnResult streamTurn(RunContext context, ChatSession session, int turn) {
        return streamer.stream(context, session, turn);
    }

    private TokenUsage appendAssistant(SessionId sessionId, ModelProfile profile,
            TurnResult result, RunEventPublisher events) {
        AssistantMessage message = AssistantMessageFactory.from(profile, result, runtime.now());
        SessionEntry entry = runtime.appendMessage(sessionId, message);
        events.messageCompleted(entry, message.stopReason(), message.usage());
        return message.usage();
    }

    private void cleanupFailedStart(ReservedRun reserved, SessionId sessionId) {
        if (reserved != null) {
            runtime.abandon(reserved.runId());
        }
        try {
            runtime.forceIdle(sessionId);
        } catch (RuntimeException error) {
            LOG.log(System.Logger.Level.WARNING, "恢复会话空闲状态失败: " + sessionId, error);
        }
    }

    private ChatSession requireSession(SessionId sessionId) {
        return sessions.findById(sessionId)
                .orElseThrow(() -> new DomainException(DomainErrorCode.SESSION_NOT_FOUND,
                        "会话不存在: " + sessionId));
    }

    /**
     * 单实例前提下自愈“数据库显示运行中、进程内已无 run”的悬空状态。
     *
     * <p>进程崩溃或重启会留下 RUNNING 会话；若内存注册表没有对应 run，
     * 说明该 run 不可能再被恢复，先回到 IDLE 再允许新消息。多实例部署时
     * 该判断不成立，届时需要持久化 run 租约，见文档中的限制说明。</p>
     */
    private ChatSession recoverStaleSession(ChatSession session) {
        boolean stuck = session.status() == SessionStatus.RUNNING
                || session.status() == SessionStatus.WAITING_APPROVAL;
        if (!stuck || runtime.findActiveBySession(session.identity().id()).isPresent()) {
            return session;
        }
        runtime.forceIdle(session.identity().id());
        return requireSession(session.identity().id());
    }

    private List<ToolCallBlock> toolCalls(LlmResponse response) {
        return response.content().stream()
                .filter(ToolCallBlock.class::isInstance)
                .map(ToolCallBlock.class::cast)
                .toList();
    }

    private String message(Throwable error) {
        String text = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return text.length() <= MAX_ERROR_LENGTH ? text : text.substring(0, MAX_ERROR_LENGTH);
    }
}
