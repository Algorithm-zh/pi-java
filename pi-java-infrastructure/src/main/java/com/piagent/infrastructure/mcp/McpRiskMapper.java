package com.piagent.infrastructure.mcp;

import com.piagent.domain.tool.model.ToolRisk;
import com.piagent.infrastructure.config.McpSettings;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * MCP 工具风险映射。
 *
 * <p>优先级：按工具名的显式覆盖 → 服务器默认级别（{@code auto} 时跳过）→
 * 名字启发式。启发式只区分 MEDIUM 与 HIGH：MCP 工具默认需要审批，
 * 只有名字明显涉及写入/执行/删除时才提升到 HIGH；CRITICAL 必须显式配置。</p>
 */
public final class McpRiskMapper {

    private static final List<String> HIGH_RISK_WORDS = List.of(
            "write", "edit", "delete", "remove", "exec", "run", "shell", "command",
            "create", "update", "move", "rename", "install", "upload", "send", "post");

    private final ToolRisk forced;
    private final Map<String, ToolRisk> overrides;

    /**
     * 创建映射器。
     *
     * @param risks 配置
     */
    public McpRiskMapper(McpSettings.Risks risks) {
        McpSettings.Risks normalized = risks == null ? McpSettings.Risks.defaults() : risks;
        this.forced = parseLevel(normalized.defaultLevel());
        Map<String, ToolRisk> parsed = new LinkedHashMap<>();
        normalized.tools().forEach((tool, level) -> parsed.put(tool, requireLevel(level)));
        this.overrides = Map.copyOf(parsed);
    }

    /**
     * 解析单个工具的风险。
     *
     * @param remoteTool 远程工具名
     * @return 风险级别
     */
    public ToolRisk riskOf(String remoteTool) {
        ToolRisk override = overrides.get(remoteTool);
        if (override != null) {
            return override;
        }
        if (forced != null) {
            return forced;
        }
        return heuristic(remoteTool);
    }

    private static ToolRisk heuristic(String remoteTool) {
        String name = remoteTool == null ? "" : remoteTool.toLowerCase(Locale.ROOT);
        for (String word : HIGH_RISK_WORDS) {
            if (name.contains(word)) {
                return ToolRisk.HIGH;
            }
        }
        return ToolRisk.MEDIUM;
    }

    private static ToolRisk parseLevel(String level) {
        if (level == null || level.isBlank() || "auto".equalsIgnoreCase(level.trim())) {
            return null;
        }
        return requireLevel(level);
    }

    private static ToolRisk requireLevel(String level) {
        try {
            return ToolRisk.valueOf(level.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException error) {
            throw new IllegalArgumentException("MCP 风险级别不合法: " + level, error);
        }
    }
}
