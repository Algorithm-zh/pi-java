package com.piagent.domain.model;

/**
 * 模型提供方协议类型。
 *
 * <p>v0.1 只实现 Fake 与 OpenAI-compatible；Anthropic 原生适配预留到 v0.2，
 * 未实现的类型在网关处显式报错，不静默降级到其他协议。
 */
public enum ProviderType {

    /** OpenAI Chat Completions 兼容协议（DeepSeek/OpenAI/Qwen/Ollama 等）。 */
    OPENAI_COMPATIBLE,

    /** Anthropic Messages API，v0.1 未实现。 */
    ANTHROPIC,

    /** 确定性测试与 demo provider，不访问网络。 */
    FAKE
}
