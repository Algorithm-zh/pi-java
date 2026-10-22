package com.piagent.application.agent.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.piagent.application.testsupport.ScriptedLlmGateway;
import com.piagent.domain.agent.AgentRun;
import com.piagent.domain.agent.RunEvent;
import com.piagent.domain.agent.RunEventType;
import com.piagent.domain.agent.RunStatus;
import com.piagent.domain.governance.model.ApprovalStatus;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionStatus;
import com.piagent.domain.session.model.message.ContentBlock;
import com.piagent.domain.session.model.message.TextBlock;
import com.piagent.domain.session.model.message.ToolCallBlock;
import com.piagent.domain.session.model.message.ToolResultMessage;
import com.piagent.domain.session.model.payload.MessagePayload;
import com.piagent.domain.tool.model.ExecutionMode;
import com.piagent.domain.tool.model.ToolRisk;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 工具审批管线的应用层单元测试。
 *
 * <p>用即时决策的审批替身验证批准、拒绝、过期、参数脱敏与审批调用串行化；
 * 真实审批的持久化与等待由独立服务测试覆盖。</p>
 */
class AgentLoopApprovalTest {

    @Test
    void approvedApprovalExecutesToolAndPublishesRedactedArguments() {
        AgentLoopFixture fixture = new AgentLoopFixture();
        fixture.tools.add("write-file", ToolRisk.MEDIUM, ExecutionMode.SEQUENTIAL);
        fixture.approvals.enqueue(ApprovalStatus.APPROVED);
        ChatSession session = fixture.createSession();
        fixture.gateway.enqueue(ScriptedLlmGateway.toolCalls(List.of(call("w1", "write-file",
                Map.of("path", "notes.txt", "apiKey", "sk-secret")))));
        fixture.gateway.enqueue(ScriptedLlmGateway.text("完成"));

        AgentRun run = fixture.run(session.identity().id(), "写文件");

        assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(run.toolCalls()).isEqualTo(1);
        assertThat(fixture.invoker.invocations()).hasSize(1);
        assertThat(fixture.approvals.contexts()).hasSize(1);
        assertThat(fixture.approvals.contexts().getFirst().arguments().digest())
                .matches("[0-9a-f]{64}");
        assertThat(fixture.approvals.contexts().getFirst().arguments().redacted())
                .containsEntry("apiKey", "***");
        ToolResultMessage result = toolResult(
                fixture.branch(session.identity().id()).get(2));
        assertThat(result.isError()).isFalse();
        Map<String, Object> started = fixture.events(run.id()).stream()
                .filter(event -> event.type() == RunEventType.TOOL_STARTED)
                .map(RunEvent::data)
                .findFirst()
                .orElseThrow();
        assertThat(started.toString()).doesNotContain("sk-secret").contains("***");
    }

    @Test
    void deniedApprovalReturnsErrorWithoutInvokingTool() {
        AgentLoopFixture fixture = new AgentLoopFixture();
        fixture.tools.add("write-file", ToolRisk.MEDIUM, ExecutionMode.SEQUENTIAL);
        fixture.approvals.enqueue(ApprovalStatus.DENIED);
        ChatSession session = fixture.createSession();
        fixture.gateway.enqueue(ScriptedLlmGateway.toolCalls(
                List.of(call("w1", "write-file", Map.of("path", "notes.txt")))));
        fixture.gateway.enqueue(ScriptedLlmGateway.text("好的"));

        AgentRun run = fixture.run(session.identity().id(), "写文件");

        assertThat(run.toolCalls()).isZero();
        assertThat(fixture.invoker.invocations()).isEmpty();
        ToolResultMessage result = toolResult(
                fixture.branch(session.identity().id()).get(2));
        assertThat(result.isError()).isTrue();
        assertThat(textOf(result.content()))
                .contains("用户拒绝执行工具 write-file: 测试拒绝");
        assertThat(fixture.session(session.identity().id()).status())
                .isEqualTo(SessionStatus.IDLE);
    }

    @Test
    void expiredApprovalReturnsErrorWithoutInvokingTool() {
        AgentLoopFixture fixture = new AgentLoopFixture();
        fixture.tools.add("write-file", ToolRisk.MEDIUM, ExecutionMode.SEQUENTIAL);
        fixture.approvals.enqueue(ApprovalStatus.EXPIRED);
        ChatSession session = fixture.createSession();
        fixture.gateway.enqueue(ScriptedLlmGateway.toolCalls(
                List.of(call("w1", "write-file", Map.of("path", "notes.txt")))));
        fixture.gateway.enqueue(ScriptedLlmGateway.text("超时了"));

        AgentRun run = fixture.run(session.identity().id(), "写文件");

        assertThat(run.toolCalls()).isZero();
        assertThat(fixture.invoker.invocations()).isEmpty();
        ToolResultMessage result = toolResult(
                fixture.branch(session.identity().id()).get(2));
        assertThat(result.isError()).isTrue();
        assertThat(textOf(result.content())).contains("审批已过期");
    }

    @Test
    void multipleApprovalsRunSequentiallyInDeclaredOrder() {
        AgentLoopFixture fixture = new AgentLoopFixture();
        fixture.tools.add("write-a", ToolRisk.MEDIUM, ExecutionMode.SEQUENTIAL)
                .add("write-b", ToolRisk.MEDIUM, ExecutionMode.SEQUENTIAL);
        fixture.approvals.enqueue(ApprovalStatus.APPROVED)
                .enqueue(ApprovalStatus.APPROVED);
        ChatSession session = fixture.createSession();
        fixture.gateway.enqueue(ScriptedLlmGateway.toolCalls(List.of(
                call("a1", "write-a", Map.of("path", "a.txt")),
                call("b1", "write-b", Map.of("path", "b.txt")))));
        fixture.gateway.enqueue(ScriptedLlmGateway.text("完成"));

        AgentRun run = fixture.run(session.identity().id(), "写两个文件");

        assertThat(run.toolCalls()).isEqualTo(2);
        assertThat(fixture.approvals.contexts()).hasSize(2);
        assertThat(fixture.invoker.invocations())
                .extracting(invocation -> invocation.tool().toString())
                .containsExactly("write-a", "write-b");
    }

    private static ToolCallBlock call(String id, String name, Map<String, Object> arguments) {
        return new ToolCallBlock(id, name, arguments);
    }

    private static Object message(SessionEntry entry) {
        return ((MessagePayload) entry.payload()).message();
    }

    private static ToolResultMessage toolResult(SessionEntry entry) {
        return (ToolResultMessage) message(entry);
    }

    private static String textOf(List<ContentBlock> blocks) {
        return blocks.stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::text)
                .reduce("", String::concat);
    }
}
