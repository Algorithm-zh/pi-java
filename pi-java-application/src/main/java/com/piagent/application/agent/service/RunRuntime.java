package com.piagent.application.agent.service;

import com.piagent.domain.agent.AgentRun;
import com.piagent.domain.agent.RunBudget;
import com.piagent.domain.agent.RunId;
import com.piagent.domain.agent.RunStatus;
import com.piagent.domain.agent.RunStopReason;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.TokenUsage;
import com.piagent.domain.session.model.message.AgentMessage;
import com.piagent.domain.session.model.payload.RunSummaryPayload;
import com.piagent.domain.shared.IdGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 运行运行时门面。
 *
 * <p>把 run 注册表、事件中心、会话追加器与时钟组装成 Agent Loop 可用的
 * 小型 API，避免循环服务直接依赖过多基础设施细节。所有 append 都是独立短事务；
 * LLM 与工具执行不持有事务。</p>
 */
@Component
public class RunRuntime {

    private static final int MAX_ERROR_LENGTH = 4_000;

    private final ActiveRunRegistry registry;
    private final RunEventHub hub;
    private final SessionEntryAppender appender;
    private final IdGenerator idGenerator;
    private final Clock clock;

    /**
     * 创建运行时。
     *
     * @param registry run 注册表
     * @param hub 事件中心
     * @param appender entry 追加器
     * @param idGenerator 标识生成器
     * @param clock 时钟
     */
    public RunRuntime(ActiveRunRegistry registry, RunEventHub hub, SessionEntryAppender appender,
            IdGenerator idGenerator, Clock clock) {
        this.registry = registry;
        this.hub = hub;
        this.appender = appender;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    /**
     * 预留一个 run 并打开事件通道。
     *
     * @param sessionId 会话标识
     * @return 运行句柄
     */
    public ReservedRun reserve(SessionId sessionId) {
        RunId runId = RunId.newId(idGenerator);
        AgentRun run = AgentRun.start(runId, sessionId, RunBudget.defaults(), clock.instant());
        registry.register(run);
        hub.open(runId, sessionId);
        return new ReservedRun(runId, new RunEventPublisher(hub, runId));
    }

    /**
     * 放弃尚未进入循环的 run。
     *
     * @param runId 运行标识
     */
    public void abandon(RunId runId) {
        registry.abandon(runId);
        hub.close(runId);
    }

    /**
     * 当前时间。
     *
     * @return UTC 时间
     */
    public Instant now() {
        return clock.instant();
    }

    /**
     * 读取 run 当前快照。
     *
     * @param runId 运行标识
     * @return 快照
     */
    public AgentRun current(RunId runId) {
        return registry.require(runId);
    }

    /**
     * 查询 run。
     *
     * @param runId 运行标识
     * @return 快照
     */
    public Optional<AgentRun> find(RunId runId) {
        return registry.find(runId);
    }

    /**
     * 查询会话的活跃 run。
     *
     * @param sessionId 会话标识
     * @return 快照
     */
    public Optional<AgentRun> findActiveBySession(SessionId sessionId) {
        return registry.findActiveBySession(sessionId);
    }

    /**
     * 是否已收到取消信号。
     *
     * @param runId 运行标识
     * @return true 表示已取消
     */
    public boolean isCancelled(RunId runId) {
        return registry.isCancelled(runId);
    }

    /**
     * 请求取消，并把信号传导到当前 LLM 流。
     *
     * @param runId 运行标识
     * @return true 表示找到 run
     */
    public boolean cancel(RunId runId) {
        return registry.cancel(runId);
    }

    /**
     * 等待 run 进入终态。
     *
     * @param runId 运行标识
     * @param timeout 最长等待时间
     * @return 终态快照
     */
    public AgentRun awaitTerminal(RunId runId, Duration timeout) {
        return registry.awaitTerminal(runId, timeout);
    }

    /**
     * 追加用户消息并启动会话状态。
     *
     * @param sessionId 会话标识
     * @param text 用户文本
     * @return entry
     */
    public SessionEntry appendUserAndStart(SessionId sessionId, String text) {
        return appender.appendUserAndStart(sessionId, text);
    }

    /**
     * 追加助手消息。
     *
     * @param sessionId 会话标识
     * @param message 消息
     * @return entry
     */
    public SessionEntry appendMessage(SessionId sessionId, AgentMessage message) {
        return appender.appendMessage(sessionId, message);
    }

    /**
     * 记录一轮模型调用。
     *
     * @param runId 运行标识
     * @param usage 本轮用量
     * @return 新快照
     */
    public AgentRun recordTurn(RunId runId, TokenUsage usage) {
        return registry.update(runId, run -> run.recordTurn(usage));
    }

    /**
     * 记录一批工具调用。
     *
     * @param runId 运行标识
     * @param executed 实际执行数
     * @return 新快照
     */
    public AgentRun recordToolCalls(RunId runId, int executed) {
        return registry.update(runId, run -> run.recordToolCalls(executed));
    }

    /**
     * 结束 run：写 RUN_SUMMARY、恢复会话为空闲、发布终态事件并关闭事件通道。
     *
     * <p>持久化摘要失败时不抛给调用线程：先尽力把会话恢复为空闲，再把 run
     * 标记为 FAILED，保证内存状态与用户可见状态一致。摘要写入失败本身会体现在
     * {@code run.failed} 事件里。</p>
     *
     * @param reserved 运行句柄
     * @param reason 终止原因
     * @param error 错误消息；非 ERROR 时可为 null
     * @return 终态快照
     */
    public AgentRun finish(ReservedRun reserved, RunStopReason reason, String error) {
        AgentRun current = registry.require(reserved.runId());
        if (current.isTerminal()) {
            return current;
        }
        AgentRun terminal = current.finish(reason, normalize(reason, error), clock.instant());
        try {
            appender.finishRun(terminal.sessionId(), summary(terminal));
        } catch (RuntimeException persistError) {
            try {
                appender.forceIdle(terminal.sessionId());
            } catch (RuntimeException ignored) {
                // 兜底恢复失败时无更多可做；run 仍会以 FAILED 结束并释放内存槽位。
            }
            terminal = current.finish(RunStopReason.ERROR,
                    truncate("写入运行摘要失败: " + message(persistError)), clock.instant());
        }
        registry.complete(terminal);
        if (terminal.status() == RunStatus.FAILED) {
            reserved.events().runFailed(terminal);
        } else {
            reserved.events().runCompleted(terminal);
        }
        hub.close(reserved.runId());
        return terminal;
    }

    /**
     * 持久化失败后的兜底：尽力恢复会话为空闲。
     *
     * @param sessionId 会话标识
     */
    public void forceIdle(SessionId sessionId) {
        appender.forceIdle(sessionId);
    }

    private RunSummaryPayload summary(AgentRun run) {
        return new RunSummaryPayload(run.id().toString(), run.stopReason().name(), run.usage(),
                run.turns(), run.toolCalls(), run.durationMillis(run.finishedAt()),
                run.errorMessage());
    }

    private String normalize(RunStopReason reason, String error) {
        if (reason == RunStopReason.ERROR) {
            return truncate(error == null || error.isBlank() ? "运行发生未知错误" : error);
        }
        return error == null ? null : truncate(error);
    }

    private String truncate(String text) {
        return text.length() <= MAX_ERROR_LENGTH ? text : text.substring(0, MAX_ERROR_LENGTH);
    }

    private String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
