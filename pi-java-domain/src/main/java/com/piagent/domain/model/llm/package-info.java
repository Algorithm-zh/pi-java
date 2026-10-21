/**
 * 流式 LLM 端口与协议无关的增量类型。
 *
 * <p>本包复用 session 上下文的消息类型作为规范消息模型，避免在模型边界再造一套
 * 文本/工具调用结构；provider 私有协议只存在于 infrastructure 适配器中。
 * 增量与最终响应都不携带 API Key 或原始 HTTP 细节。</p>
 */
package com.piagent.domain.model.llm;
