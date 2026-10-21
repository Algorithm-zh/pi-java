package com.piagent.trigger.http.tool;

import com.piagent.application.tool.readmodel.ToolView;

/**
 * 工具目录 HTTP 响应。
 *
 * <p>{@code inputSchema} 是原始 JSON Schema 文本；策略效果以字符串返回，
 * 避免前端与枚举序号耦合。</p>
 *
 * @param name 工具名
 * @param description 能力描述
 * @param inputSchema 参数 JSON Schema
 * @param risk 风险级别
 * @param executionMode 执行模式
 * @param timeoutMs 超时毫秒
 * @param effect 访问效果
 * @param requiresApproval 是否需要审批
 */
public record ToolResponse(String name, String description, String inputSchema, String risk,
        String executionMode, long timeoutMs, String effect, boolean requiresApproval) {

    /**
     * 从读模型构造响应。
     *
     * @param view 读模型
     * @return 响应
     */
    public static ToolResponse from(ToolView view) {
        return new ToolResponse(view.name(), view.description(), view.inputSchemaJson(),
                view.risk().name(), view.executionMode().name(), view.timeoutMs(),
                view.effect().name(), view.requiresApproval());
    }
}
