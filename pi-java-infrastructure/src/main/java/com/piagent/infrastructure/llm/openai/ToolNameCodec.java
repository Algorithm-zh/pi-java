package com.piagent.infrastructure.llm.openai;

import com.piagent.domain.model.llm.LlmTool;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI function name 与内部工具名的双向映射。
 *
 * <p>插件和 MCP 用点号拼接命名空间（如 {@code echo.search}），而
 * OpenAI-compatible 只接受 {@code [a-zA-Z0-9_-]} 且最长 64 个字符。
 * 请求时把名称编码成合法形式，解析工具调用时再还原，内部策略、审批与
 * 历史记录因此仍使用原名。</p>
 */
final class ToolNameCodec {

    /** OpenAI 允许的函数名最大长度。 */
    static final int MAX_LENGTH = 64;

    private static final String FALLBACK = "tool";

    private final Map<String, String> toWire;
    private final Map<String, String> toInternal;

    private ToolNameCodec(Map<String, String> toWire, Map<String, String> toInternal) {
        this.toWire = toWire;
        this.toInternal = toInternal;
    }

    /**
     * 为本次请求的工具列表建立映射。
     *
     * @param tools 工具定义
     * @return 编解码器
     */
    static ToolNameCodec of(List<LlmTool> tools) {
        Map<String, String> toWire = new HashMap<>();
        Map<String, String> toInternal = new HashMap<>();
        for (LlmTool tool : tools) {
            if (toWire.containsKey(tool.name())) {
                continue;
            }
            String wire = unique(toInternal, encode(tool.name()), tool.name());
            toWire.put(tool.name(), wire);
            toInternal.put(wire, tool.name());
        }
        return new ToolNameCodec(Map.copyOf(toWire), Map.copyOf(toInternal));
    }

    /**
     * 内部名称转请求名称。
     *
     * @param internalName 内部工具名
     * @return OpenAI 合法名称
     */
    String wire(String internalName) {
        String mapped = toWire.get(internalName);
        return mapped != null ? mapped : encode(internalName);
    }

    /**
     * 请求名称还原为内部名称。
     *
     * @param wireName 模型返回的名称
     * @return 内部工具名；未知名称原样返回
     */
    String internal(String wireName) {
        return toInternal.getOrDefault(wireName, wireName);
    }

    private static String encode(String name) {
        StringBuilder builder = new StringBuilder(Math.min(name.length(), MAX_LENGTH));
        for (int i = 0; i < name.length() && builder.length() < MAX_LENGTH; i++) {
            char value = name.charAt(i);
            builder.append(isAllowed(value) ? value : '_');
        }
        return builder.isEmpty() ? FALLBACK : builder.toString();
    }

    private static boolean isAllowed(char value) {
        return value >= 'a' && value <= 'z'
                || value >= 'A' && value <= 'Z'
                || value >= '0' && value <= '9'
                || value == '_' || value == '-';
    }

    private static String unique(Map<String, String> taken, String candidate, String name) {
        if (!taken.containsKey(candidate)) {
            return candidate;
        }
        String suffix = "-" + Integer.toHexString(name.hashCode());
        String wire = join(candidate, suffix);
        int counter = 1;
        while (taken.containsKey(wire)) {
            suffix = "-" + Integer.toHexString(name.hashCode()) + "-" + counter;
            wire = join(candidate, suffix);
            counter++;
        }
        return wire;
    }

    private static String join(String candidate, String suffix) {
        int keep = Math.max(1, MAX_LENGTH - suffix.length());
        String head = candidate.length() > keep ? candidate.substring(0, keep) : candidate;
        return head + suffix;
    }
}
