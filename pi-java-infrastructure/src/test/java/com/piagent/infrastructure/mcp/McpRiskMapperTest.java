package com.piagent.infrastructure.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.piagent.domain.tool.model.ToolRisk;
import com.piagent.infrastructure.config.McpSettings;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * MCP 工具风险映射测试。
 */
class McpRiskMapperTest {

    @Test
    void explicitToolOverrideWins() {
        McpRiskMapper mapper = new McpRiskMapper(new McpSettings.Risks("high",
                Map.of("read_doc", "low")));

        assertThat(mapper.riskOf("read_doc")).isEqualTo(ToolRisk.LOW);
        assertThat(mapper.riskOf("search")).isEqualTo(ToolRisk.HIGH);
    }

    @Test
    void autoUsesNameHeuristics() {
        McpRiskMapper mapper = new McpRiskMapper(McpSettings.Risks.defaults());

        assertThat(mapper.riskOf("search_issues")).isEqualTo(ToolRisk.MEDIUM);
        assertThat(mapper.riskOf("write_file")).isEqualTo(ToolRisk.HIGH);
        assertThat(mapper.riskOf("DELETE_record")).isEqualTo(ToolRisk.HIGH);
    }

    @Test
    void forcedDefaultAppliesToAllTools() {
        McpRiskMapper mapper = new McpRiskMapper(new McpSettings.Risks("critical", Map.of()));

        assertThat(mapper.riskOf("echo")).isEqualTo(ToolRisk.CRITICAL);
    }
}
