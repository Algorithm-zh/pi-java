package com.piagent.domain.model.llm;

/**
 * LLM 流式网关端口。
 *
 * <p>v0.1 提供 Fake 与 OpenAI-compatible 两个适配器；Anthropic 等 provider
 * 后续通过新增实现接入，领域层与应用层不需要修改。实现必须遵守
 * {@link LlmStream} 的迭代与取消约定，并自行在首个 delta 前完成有限重试。</p>
 */
public interface LlmGateway {

    /**
     * 发起流式调用。
     *
     * <p>实现应尽快返回 stream，把连接与读取推迟到首次迭代；这样调用方可以先
     * 发送 run 事件，也能在模型响应前取消。</p>
     *
     * @param request 请求
     * @param context 调用上下文
     * @return 流
     */
    LlmStream stream(LlmRequest request, LlmCallContext context);
}
