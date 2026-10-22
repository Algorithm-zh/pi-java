package com.piagent.application.subagent;

import com.piagent.application.agent.command.SendMessageCommand;
import com.piagent.application.agent.readmodel.RunHandle;
import com.piagent.application.agent.service.AgentLoopService;
import com.piagent.domain.agent.AgentRun;
import com.piagent.domain.agent.RunStopReason;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.TokenUsage;
import com.piagent.domain.session.model.message.AssistantMessage;
import com.piagent.domain.session.model.message.ContentBlock;
import com.piagent.domain.session.model.message.TextBlock;
import com.piagent.domain.session.model.payload.MessagePayload;
import com.piagent.domain.session.port.SessionEntryStore;
import com.piagent.domain.session.port.SessionRepository;
import com.piagent.domain.subagent.SubagentRequest;
import com.piagent.domain.subagent.SubagentResult;
import org.springframework.beans.factory.ObjectProvider;
import com.piagent.domain.shared.DomainException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 一次子代理执行的收尾：持久化子会话、启动子 run、等待终态并聚合结果。
 *
 * <p>为什么不直接在工具执行体里启动 {@link AgentLoopService}：子代理需要
 * “创建会话 → 冻结工具子集 → 启动 run → 有界等待 → 收集最终回答”这一串事务与等待，
 * 与工具执行体的职责（解析参数、返回结果）差别很大；分成两层可以让执行体保持极薄。</p>
 *
 * <p>等待语义：<b>同步</b>。父 run 的工具线程阻塞在 {@code awaitTerminal} 上，
 * 直到子 run 结束或超时。超时后取消子 run，并把结果标记为 {@code TIMEOUT}，
 * 父模型仍会收到一条正常的结果，从而可以自行决定下一步。</p>
 */
@Service
public class SubagentExecutionService {

    private static final System.Logger LOG =
            System.getLogger(SubagentExecutionService.class.getName());

    private final SessionRepository sessions;
    private final SessionEntryStore entries;
    private final ObjectProvider<AgentLoopService> agentLoopProvider;
    private final SubagentSessionFactory sessionFactory;

    /**
     * 创建执行服务。
     *
     * <p>Agent Loop 用 {@link ObjectProvider} 延迟解析，而不是直接构造注入。
     * 原因：子代理在工具管线内部运行（{@code AgentLoopService → TurnPipeline →
     * ToolCallCoordinator → SubagentToolInvoker → 本类 → AgentLoopService}），
     * 这是结构性的环，无法靠拆类消除。延迟解析只放开这一处，比全局开启
     * {@code allow-circular-references} 精确得多。</p>
     *
     * @param sessions 会话仓储
     * @param entries entry 存储
     * @param agentLoopProvider Agent Loop 延迟提供者
     * @param sessionFactory 子会话工厂
     */
    public SubagentExecutionService(SessionRepository sessions, SessionEntryStore entries,
            ObjectProvider<AgentLoopService> agentLoopProvider,
            SubagentSessionFactory sessionFactory) {
        this.sessions = sessions;
        this.entries = entries;
        this.agentLoopProvider = agentLoopProvider;
        this.sessionFactory = sessionFactory;
    }

    /**
     * 延迟解析 Agent Loop。
     *
     * @return 实例
     */
    private AgentLoopService agentLoop() {
        return agentLoopProvider.getObject();
    }

    /**
     * 准备子会话：构造并持久化，但尚未启动 run。
     *
     * @param parent 父会话
     * @param request 请求
     * @return 子会话
     */
    public ChatSession prepare(ChatSession parent, SubagentRequest request) {
        ChatSession child = sessionFactory.create(parent, request);
        sessions.insert(child);
        return child;
    }

    /**
     * 启动子 run 并同步等待结束。
     *
     * @param child 子会话
     * @param prompt 子代理的首条用户消息
     * @param timeout 等待上限
     * @return 结构化结果
     */
    public SubagentResult run(ChatSession child, String prompt, Duration timeout) {
        RunHandle handle = agentLoop().start(new SendMessageCommand(child.identity().id(), prompt));
        AgentRun run;
        try {
            run = agentLoop().awaitTerminal(handle.runId(), timeout);
        } catch (DomainException error) {
            // 等待超时：取消子 run，避免它继续消耗配额并写入父会话不可见的结果。
            cancelQuietly(child, handle);
            return timeoutResult(child, timeout, error);
        }
        return aggregate(child, run);
    }

    /**
     * 汇总子会话的最终回答与 run 统计。
     *
     * @param child 子会话
     * @param run 终态 run
     * @return 结果
     */
    public SubagentResult aggregate(ChatSession child, AgentRun run) {
        String summary = finalAnswer(child);
        return new SubagentResult(child.identity().id(), run.stopReason(), summary, run.usage(),
                run.durationMillis(run.finishedAt()), run.turns(), run.toolCalls());
    }

    /**
     * 父级取消时终止子 run。
     *
     * @param child 子会话
     */
    public void cancel(ChatSession child) {
        agentLoop().findActive(child.identity().id()).ifPresent(handle -> {
            try {
                agentLoop().abort(child.identity().id());
            } catch (RuntimeException error) {
                LOG.log(System.Logger.Level.DEBUG, "取消子 run 失败（可能已结束）", error);
            }
        });
    }

    /**
     * 取子会话分支上最后一条助手消息的正文。
     *
     * <p>只取最后一条助手文本：子代理中间过程的工具调用对父模型没有价值，
     * 交给父模型的是“结论”。若模型只调用了工具而没有输出文本，退化为空串，
     * 由 {@link SubagentResult#render()} 给出占位说明。</p>
     */
    private String finalAnswer(ChatSession child) {
        Optional<ChatSession> latest = sessions.findById(child.identity().id());
        if (latest.isEmpty() || latest.get().leafEntryId() == null) {
            return "";
        }
        List<SessionEntry> branch = entries.loadBranch(child.identity().id(),
                latest.get().leafEntryId());
        for (int index = branch.size() - 1; index >= 0; index--) {
            Optional<AssistantMessage> message = assistantOf(branch.get(index));
            if (message.isPresent()) {
                String text = textOf(message.get());
                if (!text.isBlank()) {
                    return text.strip();
                }
            }
        }
        return "";
    }

    private Optional<AssistantMessage> assistantOf(SessionEntry entry) {
        if (entry.payload() instanceof MessagePayload payload
                && payload.message() instanceof AssistantMessage assistant) {
            return Optional.of(assistant);
        }
        return Optional.empty();
    }

    private String textOf(AssistantMessage message) {
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : message.content()) {
            if (block instanceof TextBlock value) {
                text.append(value.text());
            }
        }
        return text.toString();
    }

    private SubagentResult timeoutResult(ChatSession child, Duration timeout, DomainException cause) {
        return new SubagentResult(child.identity().id(), RunStopReason.TIMEOUT,
                "子代理超过 " + timeout.toSeconds() + " 秒仍未完成，已取消：" + cause.getMessage(),
                TokenUsage.zero(), timeout.toMillis(), 0, 0);
    }

    private void cancelQuietly(ChatSession child, RunHandle handle) {
        try {
            agentLoop().abort(child.identity().id());
        } catch (RuntimeException error) {
            LOG.log(System.Logger.Level.DEBUG, "超时后取消子 run 失败: " + handle.runId(), error);
        }
    }
}
