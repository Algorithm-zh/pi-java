package com.piagent.domain.session.model.message;

/**
 * 消息内容块。
 *
 * <p>v0.1 只支持文本、思考与工具调用；图片等块留到 v0.2，避免半成品进入领域模型。
 */
public sealed interface ContentBlock permits TextBlock, ThinkingBlock, ToolCallBlock {
}
