package com.piagent.domain.tool.model;

import java.util.Map;
import java.util.Objects;

/**
 * 工具执行结果。
 *
 * <p>结果直接对应会话日志中的 toolResult entry：{@code content} 给模型看，
 * {@code details} 给 UI 与诊断使用，必须保持 JSON 可序列化。工具失败不抛异常，
 * 而是返回 {@code error=true} 的结果，让模型有机会自行纠正。</p>
 *
 * @param callId 调用 ID
 * @param tool 工具名
 * @param content 文本结果
 * @param error 是否失败
 * @param details 结构化细节
 */
public record ToolResult(ToolCallId callId, ToolName tool, String content, boolean error,
        Map<String, Object> details) {

    /**
     * 校验结果并冻结细节。
     *
     * @param callId 调用 ID
     * @param tool 工具名
     * @param content 文本结果
     * @param error 是否失败
     * @param details 细节
     */
    public ToolResult {
        Objects.requireNonNull(callId, "callId 不能为空");
        Objects.requireNonNull(tool, "tool 不能为空");
        content = content == null ? "" : content;
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    /**
     * 成功结果。
     *
     * @param callId 调用 ID
     * @param tool 工具名
     * @param content 文本
     * @param details 细节
     * @return 结果
     */
    public static ToolResult success(ToolCallId callId, ToolName tool, String content,
            Map<String, Object> details) {
        return new ToolResult(callId, tool, content, false, details);
    }

    /**
     * 失败结果。
     *
     * @param callId 调用 ID
     * @param tool 工具名
     * @param message 错误消息
     * @return 结果
     */
    public static ToolResult error(ToolCallId callId, ToolName tool, String message) {
        return new ToolResult(callId, tool, message, true, Map.of());
    }

    /**
     * 带细节的失败结果。
     *
     * @param callId 调用 ID
     * @param tool 工具名
     * @param message 错误消息
     * @param details 细节
     * @return 结果
     */
    public static ToolResult error(ToolCallId callId, ToolName tool, String message,
            Map<String, Object> details) {
        return new ToolResult(callId, tool, message, true, details);
    }
}
