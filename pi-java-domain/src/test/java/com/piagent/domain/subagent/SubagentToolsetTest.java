package com.piagent.domain.subagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.piagent.domain.agent.RunStopReason;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.TokenUsage;
import com.piagent.domain.shared.Ulid;
import com.piagent.domain.tool.model.ExecutionMode;
import com.piagent.domain.tool.model.ToolDescriptor;
import com.piagent.domain.tool.model.ToolName;
import com.piagent.domain.tool.model.ToolRisk;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 工具子集与结果渲染测试。
 */
class SubagentToolsetTest {

    private static final Instant NOW = Instant.parse("2026-10-22T00:00:00Z");

    private final List<ToolDescriptor> parentTools = List.of(
            tool("read"), tool("grep"), tool(SubagentToolset.SUBAGENT_TOOL));

    @Test
    void inheritsAllToolsWhenNoWhitelist() {
        SubagentRequest request = new SubagentRequest(SubagentMode.SPAWN, "任务", null, List.of(), null);

        List<ToolDescriptor> tools = SubagentToolset.derive(parentTools, request, 1,
                SubagentPolicy.withMaxDepth(3));

        assertThat(tools).extracting(tool -> tool.name().toString())
                .containsExactly("read", "grep", "subagent");
    }

    @Test
    void whitelistIntersectsWithParentTools() {
        SubagentRequest request = new SubagentRequest(SubagentMode.SPAWN, "任务", null,
                List.of("read", "write"), null);

        List<ToolDescriptor> tools = SubagentToolset.derive(parentTools, request, 1,
                SubagentPolicy.withMaxDepth(3));

        // write 不在父工具集里，不会因为白名单而出现
        assertThat(tools).extracting(tool -> tool.name().toString()).containsExactly("read");
    }

    @Test
    void removesSubagentToolWhenDepthLimitReached() {
        SubagentRequest request = new SubagentRequest(SubagentMode.SPAWN, "任务", null, List.of(), null);

        // maxDepth=2：深度 2 的子会话里再派生就是深度 3，超限，故移除 subagent
        List<ToolDescriptor> tools = SubagentToolset.derive(parentTools, request, 2,
                SubagentPolicy.withMaxDepth(2));

        assertThat(tools).extracting(tool -> tool.name().toString()).containsExactly("read", "grep");
    }

    @Test
    void keepsSubagentToolBeforeLimit() {
        SubagentRequest request = new SubagentRequest(SubagentMode.SPAWN, "任务", null, List.of(), null);

        List<ToolDescriptor> tools = SubagentToolset.derive(parentTools, request, 1,
                SubagentPolicy.withMaxDepth(2));

        assertThat(tools).extracting(tool -> tool.name().toString()).contains("subagent");
    }

    @Test
    void rendersSuccessfulResult() {
        SubagentResult result = new SubagentResult(sessionId(), RunStopReason.STOP, "分析完成",
                TokenUsage.zero(), 1_500, 3, 2);

        assertThat(result.isError()).isFalse();
        assertThat(result.render()).contains("分析完成", "轮数：3", "工具调用：2", "1500ms")
                .doesNotContain("未正常完成");
    }

    @Test
    void rendersFailureWithReasonFirst() {
        SubagentResult result = new SubagentResult(sessionId(), RunStopReason.TIMEOUT, "",
                TokenUsage.zero(), 300_000, 5, 0);

        assertThat(result.isError()).isTrue();
        assertThat(result.render()).startsWith("子代理未正常完成（TIMEOUT）")
                .contains("（子代理没有输出正文）");
    }

    @Test
    void truncatesOversizedSummary() {
        String huge = "x".repeat(SubagentResult.MAX_SUMMARY_LENGTH + 100);

        SubagentResult result = new SubagentResult(sessionId(), RunStopReason.STOP, huge,
                TokenUsage.zero(), 1, 1, 0);

        assertThat(result.summary()).endsWith("（摘要已截断）");
        assertThat(result.summary()).hasSizeLessThan(SubagentResult.MAX_SUMMARY_LENGTH + 30);
    }

    private SessionId sessionId() {
        return new SessionId(Ulid.parse("01JAVA00000000000000000001"));
    }

    private ToolDescriptor tool(String name) {
        return new ToolDescriptor(new ToolName(name), "测试工具 " + name, "{\"type\":\"object\"}",
                ToolRisk.LOW, ExecutionMode.PARALLEL, Duration.ofSeconds(5));
    }
}
