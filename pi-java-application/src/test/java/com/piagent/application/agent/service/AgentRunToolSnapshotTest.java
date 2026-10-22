package com.piagent.application.agent.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.piagent.domain.agent.AgentRun;
import com.piagent.domain.agent.RunStatus;
import com.piagent.domain.model.llm.LlmTool;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.message.ToolCallBlock;
import com.piagent.domain.tool.model.ExecutionMode;
import com.piagent.domain.tool.model.ToolRisk;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * run 级工具快照隔离测试。
 *
 * <p>模拟插件在 run 执行中被停用：实时目录立即移除工具，但该 run 仍持有
 * 开始时的快照，因此工具调用可以完成，后续 turn 也继续看到同一份清单。</p>
 */
class AgentRunToolSnapshotTest {

    @Test
    void runKeepsSnapshotWhenRegistryChangesMidRun() {
        AgentLoopFixture fixture = new AgentLoopFixture();
        fixture.tools.add("read", ToolRisk.LOW, ExecutionMode.PARALLEL);
        ChatSession session = fixture.createSession();
        SessionId sessionId = session.identity().id();
        fixture.gateway.enqueue(ScriptedLlmGateway.toolCalls(
                List.of(new ToolCallBlock("call-1", "read", Map.of("path", "README.md"))),
                () -> fixture.tools.remove("read")));
        fixture.gateway.enqueue(ScriptedLlmGateway.text("完成"));

        AgentRun run = fixture.run(sessionId, "读取 README");

        assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(run.toolCalls()).isEqualTo(1);
        assertThat(fixture.invoker.invocations())
                .extracting(invocation -> invocation.tool().value())
                .containsExactly("read");
        assertThat(fixture.tools.descriptors()).isEmpty();
        assertThat(fixture.gateway.requests()).hasSize(2);
        assertThat(fixture.gateway.requests().get(1).tools())
                .extracting(LlmTool::name)
                .containsExactly("read");
    }
}
