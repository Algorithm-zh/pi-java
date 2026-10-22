package com.piagent.trigger.http.session;

import com.piagent.domain.session.model.message.ContentBlock;
import com.piagent.domain.session.model.message.TextBlock;
import com.piagent.domain.session.model.message.ThinkingBlock;
import com.piagent.domain.session.model.message.ToolCallBlock;
import java.util.Map;

/**
 * 内容块的 HTTP 表示。
 *
 * <p>只暴露 UI 渲染需要的字段，不让前端依赖领域 sealed 类型的字段名。</p>
 *
 * @param type 块类型：text / thinking / toolCall
 * @param text 文本
 * @param thinking 思考文本
 * @param toolCallId 工具调用 ID
 * @param toolName 工具名
 * @param arguments 工具参数
 */
public record ContentBlockResponse(String type, String text, String thinking,
        String toolCallId, String toolName, Map<String, Object> arguments) {

    /**
     * 转换内容块。
     *
     * @param block 内容块
     * @return 响应
     */
    public static ContentBlockResponse from(ContentBlock block) {
        return switch (block) {
            case TextBlock text -> new ContentBlockResponse("text", text.text(), null,
                    null, null, null);
            case ThinkingBlock thinking -> new ContentBlockResponse("thinking", null,
                    thinking.thinking(), null, null, null);
            case ToolCallBlock call -> new ContentBlockResponse("toolCall", null, null,
                    call.id(), call.name(), call.arguments());
        };
    }
}
