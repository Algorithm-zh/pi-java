package com.piagent.domain.session.model.message;

/**
 * 消息内容块。
 *
 * <p>文本、思考、工具调用与图片；图片只保存附件引用，二进制内容留在附件存储中，
 * 避免把大块 base64 写进会话日志与数据库。</p>
 */
public sealed interface ContentBlock permits TextBlock, ThinkingBlock, ToolCallBlock, ImageBlock {
}
