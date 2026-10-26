package com.piagent.application.agent.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.piagent.application.testsupport.ScriptedLlmGateway;
import com.piagent.application.verify.VerificationOptions;
import com.piagent.domain.agent.AgentRun;
import com.piagent.domain.agent.RunEvent;
import com.piagent.domain.agent.RunEventType;
import com.piagent.domain.agent.RunStatus;
import com.piagent.domain.governance.model.ApprovalStatus;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.EntryType;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.message.TextBlock;
import com.piagent.domain.session.model.message.ToolCallBlock;
import com.piagent.domain.session.model.message.UserMessage;
import com.piagent.domain.session.model.payload.MessagePayload;
import com.piagent.domain.session.model.payload.VerificationPayload;
import com.piagent.domain.tool.model.ExecutionMode;
import com.piagent.domain.tool.model.ToolRisk;
import com.piagent.domain.verify.model.VerificationOutcome;
import com.piagent.domain.verify.port.VerificationRunner;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 自动验证与 Agent Loop 的集成测试。
 */
class AgentLoopVerificationTest {

    @Test
    void failedVerificationWritesEntryAndFeedsOutputBackOnce() {
        QueueRunner runner = new QueueRunner(failure());
        AgentLoopFixture fixture = fixture(runner, true);
        fixture.tools.add("write", ToolRisk.HIGH, ExecutionMode.SEQUENTIAL);
        fixture.approvals.enqueue(ApprovalStatus.APPROVED);
        ChatSession session = fixture.createSession();
        fixture.gateway.enqueue(ScriptedLlmGateway.toolCalls(
                List.of(call("call-1", "write"))));
        fixture.gateway.enqueue(ScriptedLlmGateway.text("改好了"));
        fixture.gateway.enqueue(ScriptedLlmGateway.text("测试已修复"));

        AgentRun run = fixture.run(session.identity().id(), "改代码");

        assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(runner.calls).isEqualTo(1);
        List<SessionEntry> branch = fixture.branch(session.identity().id());
        assertThat(branch).extracting(SessionEntry::type)
                .containsSubsequence(EntryType.MESSAGE, EntryType.VERIFICATION,
                        EntryType.MESSAGE);
        VerificationPayload payload = (VerificationPayload) branch.stream()
                .filter(entry -> entry.type() == EntryType.VERIFICATION)
                .findFirst().orElseThrow().payload();
        assertThat(payload.passed()).isFalse();
        assertThat(payload.exitCode()).isEqualTo(1);
        assertThat(userTexts(branch)).anySatisfy(text ->
                assertThat(text).contains("自动验证未通过", "mvn -q test", "Tests run: 1"));

        Map<String, Object> event = fixture.events(run.id()).stream()
                .filter(item -> item.type() == RunEventType.VERIFICATION_COMPLETED)
                .map(RunEvent::data)
                .findFirst().orElseThrow();
        assertThat(event).containsEntry("passed", false).containsEntry("command", "mvn -q test");
    }

    @Test
    void passingVerificationDoesNotFeedBack() {
        QueueRunner runner = new QueueRunner(passing());
        AgentLoopFixture fixture = fixture(runner, true);
        fixture.tools.add("write", ToolRisk.HIGH, ExecutionMode.SEQUENTIAL);
        fixture.approvals.enqueue(ApprovalStatus.APPROVED);
        ChatSession session = fixture.createSession();
        fixture.gateway.enqueue(ScriptedLlmGateway.toolCalls(
                List.of(call("call-1", "write"))));
        fixture.gateway.enqueue(ScriptedLlmGateway.text("完成"));

        AgentRun run = fixture.run(session.identity().id(), "改代码");

        assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(runner.calls).isEqualTo(1);
        List<SessionEntry> branch = fixture.branch(session.identity().id());
        assertThat(branch).filteredOn(entry -> entry.type() == EntryType.VERIFICATION)
                .singleElement()
                .satisfies(entry -> assertThat(((VerificationPayload) entry.payload()).passed())
                        .isTrue());
        assertThat(userTexts(branch)).noneSatisfy(text ->
                assertThat(text).contains("自动验证未通过"));
    }

    @Test
    void verificationSkippedWhenRunDidNotMutateFiles() {
        QueueRunner runner = new QueueRunner(failure());
        AgentLoopFixture fixture = fixture(runner, true);
        fixture.tools.add("read", ToolRisk.LOW, ExecutionMode.SEQUENTIAL);
        ChatSession session = fixture.createSession();
        fixture.gateway.enqueue(ScriptedLlmGateway.toolCalls(
                List.of(new ToolCallBlock("call-1", "read", Map.of("path", "README.md")))));
        fixture.gateway.enqueue(ScriptedLlmGateway.text("看完了"));

        AgentRun run = fixture.run(session.identity().id(), "看文件");

        assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(runner.calls).isZero();
        assertThat(fixture.branch(session.identity().id())).extracting(SessionEntry::type)
                .doesNotContain(EntryType.VERIFICATION);
    }

    @Test
    void failureIsRecordedWithoutFeedbackWhenRetryDisabled() {
        QueueRunner runner = new QueueRunner(failure());
        AgentLoopFixture fixture = new AgentLoopFixture(runner,
                new VerificationOptions("mvn -q test", 5, 4000, false));
        fixture.tools.add("write", ToolRisk.HIGH, ExecutionMode.SEQUENTIAL);
        fixture.approvals.enqueue(ApprovalStatus.APPROVED);
        ChatSession session = fixture.createSession();
        fixture.gateway.enqueue(ScriptedLlmGateway.toolCalls(
                List.of(call("call-1", "write"))));
        fixture.gateway.enqueue(ScriptedLlmGateway.text("完成"));

        AgentRun run = fixture.run(session.identity().id(), "改代码");

        List<SessionEntry> branch = fixture.branch(session.identity().id());
        assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(branch).extracting(SessionEntry::type).contains(EntryType.VERIFICATION);
        assertThat(userTexts(branch)).noneSatisfy(text ->
                assertThat(text).contains("自动验证未通过"));
    }

    private static AgentLoopFixture fixture(VerificationRunner runner, boolean retry) {
        return new AgentLoopFixture(runner,
                new VerificationOptions("mvn -q test", 5, 4000, retry));
    }

    private static List<String> userTexts(List<SessionEntry> branch) {
        return branch.stream()
                .filter(entry -> entry.type() == EntryType.MESSAGE)
                .map(SessionEntry::payload)
                .filter(MessagePayload.class::isInstance)
                .map(MessagePayload.class::cast)
                .map(MessagePayload::message)
                .filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .map(message -> ((TextBlock) message.content().getFirst()).text())
                .toList();
    }

    private static ToolCallBlock call(String callId, String toolName) {
        return new ToolCallBlock(callId, toolName, Map.of("path", "src/App.java",
                "content", "hello"));
    }

    private static VerificationOutcome failure() {
        return new VerificationOutcome("mvn -q test", 1, false, false, 1200,
                "Tests run: 1, Failures: 1");
    }

    private static VerificationOutcome passing() {
        return new VerificationOutcome("mvn -q test", 0, false, false, 900,
                "BUILD SUCCESS");
    }

    private static final class QueueRunner implements VerificationRunner {

        private final Deque<VerificationOutcome> outcomes = new ArrayDeque<>();
        private int calls;

        QueueRunner(VerificationOutcome outcome) {
            outcomes.add(outcome);
        }

        @Override
        public VerificationOutcome run(String command, Path workspace, Duration timeout,
                int maxOutputChars) {
            calls++;
            return outcomes.isEmpty() ? passing() : outcomes.removeFirst();
        }
    }
}
