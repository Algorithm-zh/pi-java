package com.piagent.trigger.http.session;

import com.piagent.domain.model.Usage;
import com.piagent.domain.session.model.TokenUsage;

/**
 * 前端展示用的 token 用量。
 *
 * @param input 输入 token
 * @param output 输出 token
 * @param cacheRead 缓存读 token
 * @param cacheWrite 缓存写 token
 * @param totalTokens 总量
 */
public record UsageResponse(long input, long output, long cacheRead, long cacheWrite,
        long totalTokens) {

    /**
     * 从会话用量转换。
     *
     * @param usage 会话用量
     * @return 响应
     */
    public static UsageResponse from(TokenUsage usage) {
        return new UsageResponse(usage.input(), usage.output(), usage.cacheRead(),
                usage.cacheWrite(), usage.totalTokens());
    }

    /**
     * 从模型用量转换。
     *
     * @param usage 模型用量
     * @return 响应
     */
    public static UsageResponse from(Usage usage) {
        return new UsageResponse(usage.input(), usage.output(), usage.cacheRead(),
                usage.cacheWrite(), usage.totalTokens());
    }
}
