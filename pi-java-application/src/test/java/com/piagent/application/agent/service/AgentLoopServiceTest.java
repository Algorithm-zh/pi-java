package com.piagent.application.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.application.agent.command.SendMessageCommand;
import com.piagent.application.agent.readmodel.RunHandle;
import com.piagent.application.testsupport.SequentialIdGenerator;
import com.piagent.domain.agent.AgentRun;
import com.piagent.domain.agent.RunEvent;
import com.piagent.domain.agent.RunEventType;
import com.piagent.domain.agent.RunStatus;
import com.piagent.domain.agent.RunStopReason;
import com.piagent.domain.governance.model.ApprovalMode;
import com.piagent.domain.governance.model.PolicyEffect;
import com.piagent.domain.governance.model.ToolPolicyKey;
import com.piagent.domain.governance.model.ToolPolicyRule;
import com.piagent.domain.governance.model.ToolPolicySettings;
import com.piagent.domain.governance.model.WorkspaceKey;
import com.piagent.domain.model.llm.LlmErrorCode;
import com.piagent.domain.model.llm.LlmGatewayException;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.EntryType;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.SessionStatus;
import com.piagent.domain.session.model.message.AssistantMessage;
import com.piagent.domain.session.model.message.AssistantStopReason;
import com.piagent.domain.session.model.message.ContentBlock;
import com.piagent.domain.session.model.message.TextBlock;
import com.piagent.domain.session.model.message.ToolCallBlock;
import com.piagent.domain.session.model.message.ToolResultMessage;
import com.piagent.domain.session.model.payload.MessagePayload;
import com.piagent.domain.session.model.payload.RunSummaryPayload;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.tool.model.ExecutionMode;
import com.piagent.domain.tool.model.ToolName;
import com.piagent.domain.tool.model.ToolRisk;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Agent Loop 应用层单元测试。
 *
 * <p>使用 {@link AgentLoopFixture} 装配真实的状态机、追加器与协调器，
 * 只替换 LLM、工具与持久化端口，覆盖终止判定、工具配对、审批拒绝、
 * 取消、错误收尾与预算耗尽等关键路径。</p>
 */
class AgentLoopServiceTest {

    @Test
    void completesTextOnlyRun() {
        AgentLoopFixture fixture = new AgentLoopFixture();
        ChatSession session = fixture.createSession();
        SessionId sessionId = session.identity().id();
        AtomicReference<Optional<RunHandle>> active = new AtomicReference<>();
        fixture.gateway.enqueue(ScriptedLlmGateway.text("你好",
                () -> active.set(fixture.loop.findActive(sessionId))));

        AgentRun run = fixture.run(sessionId, "打个招呼");

        assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(run.stopReason()).isEqualTo(RunStopReason.STOP);
        assertThat(run.turns()).isEqualTo(1);
        assertThat(run.toolCalls()).isZero();
        assertThat(fixture.loop.findRun(run.id())).isPresent();
        assertThat(fixture.loop.findActive(sessionId)).isEmpty();
        assertThat(active.get()).isPresent();
        assertThat(fixture.registry.findActiveBySession(sessionId)).isEmpty();
        assertThat(fixture.session(sessionId).status()).isEqualTo(SessionStatus.IDLE);

        List<SessionEntry> branch = fixture.branch(sessionId);
        assertThat(branch).extracting(SessionEntry::type)
                .containsExactly(EntryType.MESSAGE, EntryType.MESSAGE, EntryType.RUN_SUMMARY);
        AssistantMessage assistant = (AssistantMessage) message(branch.get(1));
        assertThat(assistant.stopReason()).isEqualTo(AssistantStopReason.STOP);
        assertThat(textOf(assistant.content())).isEqualTo("你好");
        assertThat(summary(branch.get(2)).stopReason()).isEqualTo("STOP");
        assertThat(summary(branch.get(2)).turns()).isEqualTo(1);
        assertThat(types(fixture.events(run.id())))
                .containsSubsequence(RunEventType.RUN_STARTED, RunEventType.TURN_STARTED,
                        RunEventType.MESSAGE_STARTED, RunEventType.MESSAGE_DELTA,
                        RunEventType.MESSAGE_COMPLETED, RunEventType.RUN_COMPLETED);
    }

    @Test
    void executesToolAndContinuesToNextTurn() {
        AgentLoopFixture fixture = new AgentLoopFixture();
        fixture.tools.add("read", ToolRisk.LOW, ExecutionMode.SEQUENTIAL);
        ChatSession session = fixture.createSession();
        fixture.gateway.enqueue(ScriptedLlmGateway.toolCalls(List.of(call("call-1", "read"))));
        fixture.gateway.enqueue(ScriptedLlmGateway.text("已读取"));

        AgentRun run = fixture.run(session.identity().id(), "读一下");

        assertThat(run.turns()).isEqualTo(2);
        assertThat(run.toolCalls()).isEqualTo(1);
        assertThat(fixture.invoker.invocations()).hasSize(1);
        assertThat(fixture.invoker.invocations().getFirst().callId().toString())
                .isEqualTo("call-1");
        List<SessionEntry> branch = fixture.branch(session.identity().id());
        assertThat(branch).extracting(SessionEntry::type).containsExactly(EntryType.MESSAGE,
                EntryType.MESSAGE, EntryType.MESSAGE, EntryType.MESSAGE, EntryType.RUN_SUMMARY);
        ToolResultMessage result = toolResult(branch.get(2));
        assertThat(result.toolCallId()).isEqualTo("call-1");
        assertThat(result.toolName()).isEqualTo("read");
        assertThat(result.isError()).isFalse();
        assertThat(textOf(result.content())).isEqualTo("tool:read");
        assertThat(textOf(((AssistantMessage) message(branch.get(3))).content()))
                .isEqualTo("已读取");
        assertThat(summary(branch.get(4)).turns()).isEqualTo(2);
        assertThat(summary(branch.get(4)).toolCalls()).isEqualTo(1);
    }

    @Test
    void executesParallelToolsInDeclaredOrder() {
        AgentLoopFixture fixture = new AgentLoopFixture();
        fixture.tools.add("read-a", ToolRisk.LOW, ExecutionMode.PARALLEL)
                .add("read-b", ToolRisk.LOW, ExecutionMode.PARALLEL);
        ChatSession session = fixture.createSession();
        fixture.gateway.enqueue(ScriptedLlmGateway.toolCalls(
                List.of(call("c1", "read-a"), call("c2", "read-b"))));
        fixture.gateway.enqueue(ScriptedLlmGateway.text("完成"));

        AgentRun run = fixture.run(session.identity().id(), "并行读");

        assertThat(run.toolCalls()).isEqualTo(2);
        assertThat(fixture.invoker.invocations())
                .extracting(invocation -> invocation.tool().toString())
                .containsExactly("read-a", "read-b");
        assertThat(toolResult(fixture.branch(session.identity().id()).get(3)).toolCallId())
                .isEqualTo("c2");
    }

    @Test
    void workspaceRuleCanDenyTool() {
        AgentLoopFixture fixture = new AgentLoopFixture();
        fixture.tools.add("shell", ToolRisk.LOW, ExecutionMode.SEQUENTIAL);
        fixture.policies.insert(ToolPolicyRule.create(
                ToolPolicyKey.workspace(WorkspaceKey.of(AgentLoopFixture.WORKSPACE),
                        new ToolName("shell")),
                new ToolPolicySettings(PolicyEffect.DENY, ApprovalMode.ONCE,
                        ToolRisk.LOW, null, Map.of())));
        ChatSession session = fixture.createSession();
        fixture.gateway.enqueue(ScriptedLlmGateway.toolCalls(List.of(call("s1", "shell"))));
        fixture.gateway.enqueue(ScriptedLlmGateway.text("已拒绝"));

        AgentRun run = fixture.run(session.identity().id(), "执行命令");

        assertThat(run.toolCalls()).isZero();
        ToolResultMessage result = toolResult(fixture.branch(session.identity().id()).get(2));
        assertThat(result.isError()).isTrue();
        assertThat(textOf(result.content())).contains("策略拒绝执行");
    }

    @Test
    void unknownToolGetsPairedErrorResult() {
        AgentLoopFixture fixture = new AgentLoopFixture();
        ChatSession session = fixture.createSession();
        fixture.gateway.enqueue(ScriptedLlmGateway.toolCalls(
                List.of(call("missing-1", "missing"))));
        fixture.gateway.enqueue(ScriptedLlmGateway.text("换个方式"));

        AgentRun run = fixture.run(session.identity().id(), "调不存在的工具");

        assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(run.toolCalls()).isZero();
        assertThat(fixture.invoker.invocations()).isEmpty();
        ToolResultMessage result = toolResult(fixture.branch(session.identity().id()).get(2));
        assertThat(result.toolName()).isEqualTo("missing");
        assertThat(result.isError()).isTrue();
        assertThat(textOf(result.content())).contains("工具未注册");
    }

    @Test
    void cancellationKeepsPartialAssistantMessage() {
        AgentLoopFixture fixture = new AgentLoopFixture();
        ChatSession session = fixture.createSession();
        SessionId sessionId = session.identity().id();
        fixture.gateway.enqueue(ScriptedLlmGateway.text("半截",
                () -> fixture.loop.abort(sessionId)));

        AgentRun run = fixture.run(sessionId, "开始");

        assertThat(run.status()).isEqualTo(RunStatus.ABORTED);
        assertThat(run.stopReason()).isEqualTo(RunStopReason.CANCELLED);
        assertThat(run.turns()).isEqualTo(1);
        List<SessionEntry> branch = fixture.branch(sessionId);
        assertThat(branch).hasSize(3);
        AssistantMessage assistant = (AssistantMessage) message(branch.get(1));
        assertThat(assistant.stopReason()).isEqualTo(AssistantStopReason.ABORTED);
        assertThat(textOf(assistant.content())).isEqualTo("半截");
        assertThat(summary(branch.get(2)).stopReason()).isEqualTo("CANCELLED");
        assertThat(fixture.session(sessionId).status()).isEqualTo(SessionStatus.IDLE);
        assertThat(types(fixture.events(run.id())))
                .containsSubsequence(RunEventType.MESSAGE_COMPLETED, RunEventType.RUN_COMPLETED)
                .doesNotContain(RunEventType.RUN_FAILED);
    }

    @Test
    void modelFailureFinishesRunWithoutAssistantMessage() {
        AgentLoopFixture fixture = new AgentLoopFixture();
        ChatSession session = fixture.createSession();
        fixture.gateway.enqueue(ScriptedLlmGateway.failure(
                new LlmGatewayException(LlmErrorCode.SERVER_ERROR, "boom")));

        AgentRun run = fixture.run(session.identity().id(), "你好");

        assertThat(run.status()).isEqualTo(RunStatus.FAILED);
        assertThat(run.stopReason()).isEqualTo(RunStopReason.ERROR);
        assertThat(run.errorMessage()).contains("模型调用失败: boom");
        assertThat(run.turns()).isZero();
        List<SessionEntry> branch = fixture.branch(session.identity().id());
        assertThat(branch).extracting(SessionEntry::type)
                .containsExactly(EntryType.MESSAGE, EntryType.RUN_SUMMARY);
        assertThat(summary(branch.get(1)).stopReason()).isEqualTo("ERROR");
        assertThat(fixture.session(session.identity().id()).status())
                .isEqualTo(SessionStatus.IDLE);
        assertThat(types(fixture.events(run.id()))).contains(RunEventType.RUN_FAILED);
    }

    @Test
    void recoversStaleRunningSessionBeforeStarting() {
        AgentLoopFixture fixture = new AgentLoopFixture();
        ChatSession session = fixture.createSession();
        fixture.sessions.update(session.markRunning());
        fixture.gateway.enqueue(ScriptedLlmGateway.text("恢复了"));

        AgentRun run = fixture.run(session.identity().id(), "继续");

        assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(fixture.session(session.identity().id()).status())
                .isEqualTo(SessionStatus.IDLE);
        assertThat(fixture.branch(session.identity().id())).hasSize(3);
    }

    @Test
    void missingModelCleansUpReservationAndSessionState() {
        AgentLoopFixture fixture = new AgentLoopFixture();
        ChatSession session = fixture.createSession("missing-model");

        assertThatThrownBy(() -> fixture.loop.start(
                new SendMessageCommand(session.identity().id(), "你好")))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.MODEL_NOT_FOUND));

        assertThat(fixture.session(session.identity().id()).status())
                .isEqualTo(SessionStatus.IDLE);
        assertThat(fixture.registry.findActiveBySession(session.identity().id())).isEmpty();
    }

    @Test
    void rejectsWhenConcurrentRunLimitReached() {
        AgentLoopFixture fixture = new AgentLoopFixture(1);
        ChatSession first = fixture.createSession();
        SessionId secondId = fixture.createSession().identity().id();
        AtomicReference<DomainException> busy = new AtomicReference<>();
        fixture.gateway.enqueue(ScriptedLlmGateway.text("第一个", () -> {
            try {
                fixture.loop.start(new SendMessageCommand(secondId, "第二个"));
            } catch (DomainException error) {
                busy.set(error);
            }
        }));

        AgentRun run = fixture.run(first.identity().id(), "开始");

        assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(busy.get()).isNotNull();
        assertThat(busy.get().code()).isEqualTo(DomainErrorCode.AGENT_BUSY);
        assertThat(fixture.session(secondId).status()).isEqualTo(SessionStatus.IDLE);
    }

    @Test
    void rejectsUnknownSessionAndInactiveAbort() {
        AgentLoopFixture fixture = new AgentLoopFixture();
        ChatSession session = fixture.createSession();
        SequentialIdGenerator generator = new SequentialIdGenerator();
        generator.newId();
        SessionId ghost = SessionId.newId(generator);

        assertThatThrownBy(() -> fixture.loop.start(new SendMessageCommand(ghost, "你好")))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.SESSION_NOT_FOUND));
        assertThatThrownBy(() -> fixture.loop.abort(session.identity().id()))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.RUN_NOT_FOUND));
    }

    @Test
    void stopsAtMaxTurns() {
        AgentLoopFixture fixture = new AgentLoopFixture();
        fixture.tools.add("read", ToolRisk.LOW, ExecutionMode.SEQUENTIAL);
        ChatSession session = fixture.createSession();
        IntStream.range(0, 20).forEach(index -> fixture.gateway.enqueue(
                ScriptedLlmGateway.toolCalls(List.of(call("c" + index, "read")))));

        AgentRun run = fixture.run(session.identity().id(), "循环");

        assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(run.stopReason()).isEqualTo(RunStopReason.MAX_STEPS);
        assertThat(run.turns()).isEqualTo(20);
        assertThat(run.toolCalls()).isEqualTo(20);
        assertThat(run.errorMessage()).contains("达到最大模型轮数");
    }

    @Test
    void stopsWhenToolBudgetExhausted() {
        AgentLoopFixture fixture = new AgentLoopFixture();
        fixture.tools.add("read", ToolRisk.LOW, ExecutionMode.PARALLEL);
        ChatSession session = fixture.createSession();
        List<ToolCallBlock> calls = IntStream.range(0, 101)
                .mapToObj(index -> call("c" + index, "read"))
                .toList();
        fixture.gateway.enqueue(ScriptedLlmGateway.toolCalls(calls));

        AgentRun run = fixture.run(session.identity().id(), "一口气读");

        assertThat(run.stopReason()).isEqualTo(RunStopReason.MAX_STEPS);
        assertThat(run.toolCalls()).isEqualTo(100);
        assertThat(run.errorMessage()).contains("达到最大工具调用次数");
        assertThat(fixture.invoker.invocations()).hasSize(100);
        List<SessionEntry> branch = fixture.branch(session.identity().id());
        assertThat(branch).hasSize(104);
        assertThat(textOf(toolResult(branch.get(102)).content()))
                .contains("工具调用上限");
    }

    @Test
    void appenderRejectsStartWhenSessionNotIdle() {
        AgentLoopFixture fixture = new AgentLoopFixture();
        ChatSession session = fixture.createSession();
        fixture.sessions.update(session.markRunning());

        assertThatThrownBy(() -> fixture.appender.appendUserAndStart(
                session.identity().id(), "重复启动"))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.INVALID_STATE_TRANSITION));
    }

    private static ToolCallBlock call(String id, String name) {
        return new ToolCallBlock(id, name, Map.of());
    }

    private static Object message(SessionEntry entry) {
        return ((MessagePayload) entry.payload()).message();
    }

    private static ToolResultMessage toolResult(SessionEntry entry) {
        return (ToolResultMessage) message(entry);
    }

    private static RunSummaryPayload summary(SessionEntry entry) {
        return (RunSummaryPayload) entry.payload();
    }

    private static String textOf(List<ContentBlock> blocks) {
        return blocks.stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::text)
                .reduce("", String::concat);
    }

    private static List<RunEventType> types(List<RunEvent> events) {
        return events.stream().map(RunEvent::type).toList();
    }
}
