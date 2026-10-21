/**
 * LLM 适配器基础设施。
 *
 * <p>领域层通过 {@code LlmGateway} 端口调用；本包负责 provider 路由、Fake 演示、
 * OpenAI-compatible SSE 解析与错误映射。API Key 只在请求发送瞬间从环境变量读取。</p>
 */
package com.piagent.infrastructure.llm;
