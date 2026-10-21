package com.piagent.domain.session.model;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;

/**
 * 会话侧规范化的 token 用量。
 *
 * <p>模型适配器上报的 provider-specific 用量先映射为本类型，再进入消息 payload 与
 * run summary；所有字段非负。
 *
 * @param input 输入 token
 * @param output 输出 token
 * @param cacheRead 命中缓存的输入 token
 * @param cacheWrite 写入缓存的 token
 * @param totalTokens 总 token
 */
public record TokenUsage(long input, long output, long cacheRead, long cacheWrite, long totalTokens) {

    /**
     * 校验 token 用量。
     *
     * @param input 输入
     * @param output 输出
     * @param cacheRead 缓存读
     * @param cacheWrite 缓存写
     * @param totalTokens 总量
     */
    public TokenUsage {
        Guard.require(input >= 0 && output >= 0 && cacheRead >= 0 && cacheWrite >= 0 && totalTokens >= 0,
                DomainErrorCode.INVALID_ARGUMENT, "token 用量不能为负数");
    }

    /**
     * 零用量。
     *
     * @return 全零用量
     */
    public static TokenUsage zero() {
        return new TokenUsage(0, 0, 0, 0, 0);
    }
}
