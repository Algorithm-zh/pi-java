package com.piagent.infrastructure.mcp;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.tool.model.ToolName;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * MCP 工具命名空间。
 *
 * <p>远程工具名可能包含大写、空格或其他不属于 {@link ToolName} 的字符，因此统一
 * 清洗后再加服务器前缀：{@code <server>.<tool>}。这样模型、策略表与审批记录里
 * 看到的名称稳定且可读，也不会与内置工具冲突。</p>
 */
public final class McpToolNamespace {

    /** 与 {@link ToolName} 一致的最大长度。 */
    static final int MAX_LENGTH = 128;

    private static final Pattern INVALID_RUNS = Pattern.compile("[^a-z0-9._-]+");
    private static final Pattern SEPARATOR_RUNS = Pattern.compile("[._-]{2,}");
    private static final Pattern LEADING_SEPARATORS = Pattern.compile("^[._-]+");
    private static final Pattern TRAILING_SEPARATORS = Pattern.compile("[._-]+$");

    private McpToolNamespace() {
    }

    /**
     * 拼接完整工具名。
     *
     * @param server 服务器名
     * @param remoteTool 远程工具名
     * @return 完整工具名
     */
    public static String qualify(String server, String remoteTool) {
        String tool = sanitize(remoteTool);
        String prefix = server + ".";
        if (prefix.length() + tool.length() <= MAX_LENGTH) {
            return prefix + tool;
        }
        String hash = Integer.toHexString(remoteTool == null ? 0 : remoteTool.hashCode());
        int keep = Math.max(1, MAX_LENGTH - prefix.length() - hash.length() - 1);
        String head = trimSeparators(tool.length() > keep ? tool.substring(0, keep) : tool);
        return prefix + (head.isEmpty() ? "tool" : head) + "-" + hash;
    }

    /**
     * 清洗远程工具名。
     *
     * @param remoteTool 原始名称
     * @return 符合 {@link ToolName} 规则的名称段
     */
    public static String sanitize(String remoteTool) {
        String value = remoteTool == null ? "" : remoteTool.trim().toLowerCase(Locale.ROOT);
        value = trimSeparators(INVALID_RUNS.matcher(value).replaceAll("-"));
        value = SEPARATOR_RUNS.matcher(value).replaceAll("-");
        if (value.isEmpty()) {
            value = "tool";
        }
        if (!Character.isLetter(value.charAt(0))) {
            value = "tool-" + value;
        }
        return value;
    }

    /**
     * 要求工具名属于指定服务器命名空间。
     *
     * @param server 服务器名
     * @param name 完整工具名
     */
    public static void requireOwned(String server, ToolName name) {
        if (name == null || !name.value().startsWith(server + ".")) {
            throw new DomainException(DomainErrorCode.MCP_TOOL_CONFLICT,
                    "MCP 工具必须以 " + server + ". 为前缀: " + name);
        }
    }

    private static String trimSeparators(String value) {
        String trimmed = LEADING_SEPARATORS.matcher(value).replaceAll("");
        return TRAILING_SEPARATORS.matcher(trimmed).replaceAll("");
    }
}
