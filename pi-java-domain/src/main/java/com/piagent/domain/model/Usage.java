package com.piagent.domain.model;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;

/**
 * LLM 调用上报的 token 用量。
 *
 * <p>这是模型上下文的用量值对象，与 session 上下文的 {@code TokenUsage} 字段一致但
 * 语义边界不同：本类型描述一次 provider 响应，由应用层翻译为会话消息用量。
 * 所有字段非负。</p>
 *
 * @param input 输入 token
 * @param output 输出 token
 * @param cacheRead 命中缓存的输入 token
 * @param cacheWrite 写入缓存的 token
 * @param totalTokens 总 token
 */
public record Usage(long input, long output, long cacheRead, long cacheWrite, long totalTokens) {

    /**
     * 校验用量。
     *
     * @param input 输入
     * @param output 输出
     * @param cacheRead 缓存读
     * @param cacheWrite 缓存写
     * @param totalTokens 总量
     */
    public Usage {
        Guard.require(input >= 0 && output >= 0 && cacheRead >= 0 && cacheWrite >= 0 && totalTokens >= 0,
                DomainErrorCode.INVALID_ARGUMENT, "token 用量不能为负数");
    }

    /**
     * 零用量。
     *
     * @return 全零用量
     */
    public static Usage zero() {
        return new Usage(0, 0, 0, 0, 0);
    }

    /**
     * 返回输入与输出之和，provider 未上报总量时使用。
     *
     * @return input + output
     */
    public long inputAndOutput() {
        return input + output;
    }
}
