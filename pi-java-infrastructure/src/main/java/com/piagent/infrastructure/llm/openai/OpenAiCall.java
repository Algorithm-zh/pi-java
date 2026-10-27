package com.piagent.infrastructure.llm.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.piagent.domain.model.llm.LlmCallContext;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;

/**
 * 一次 OpenAI-compatible HTTP 调用的不可变参数集合。
 *
 * <p>把超过六个依赖打包，避免流实现构造函数膨胀；HTTP 请求可安全复用于
 * 重试，密钥只存在于 Authorization 头中，不在任何字段或日志里单独保存。</p>
 *
 * @param client HTTP 客户端
 * @param request 已签名的 HTTP 请求
 * @param context 调用上下文
 * @param mapper Jackson 映射器
 * @param errorMapper HTTP 错误映射器
 * @param names 工具名编解码器，用于还原模型返回的工具名
 */
record OpenAiCall(HttpClient client, HttpRequest request, LlmCallContext context,
        ObjectMapper mapper, OpenAiErrorMapper errorMapper, ToolNameCodec names) {
}
