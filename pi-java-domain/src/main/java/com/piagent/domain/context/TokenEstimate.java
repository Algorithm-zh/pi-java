package com.piagent.domain.context;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.util.Objects;

/**
 * token 估算值及其来源。
 *
 * <p>估算只用于压缩阈值与摘要收敛判断，不要求与 provider 完全一致。
 * 一旦拿到真实的 usage，可用 {@link Method#USAGE_ANCHOR} 校准，避免字符启发式
 * 在英文/代码场景下系统性偏差。</p>
 *
 * @param tokens 估算 token 数
 * @param method 估算方法
 */
public record TokenEstimate(int tokens, Method method) {

    /**
     * 估算方法。
     */
    public enum Method {

        /** 字符启发式：CJK 按 1 字符 1 token，其余按 4 字符 1 token。 */
        HEURISTIC,

        /** 使用 provider 上报的输入 token 作为锚点。 */
        USAGE_ANCHOR
    }

    /**
     * 校验估算值。
     *
     * @param tokens token 数
     * @param method 方法
     */
    public TokenEstimate {
        Guard.require(tokens >= 0, DomainErrorCode.INVALID_ARGUMENT, "tokens 不能为负数");
        Objects.requireNonNull(method, "method 不能为空");
    }

    /**
     * 创建启发式估算。
     *
     * @param tokens token 数
     * @return 估算值
     */
    public static TokenEstimate heuristic(int tokens) {
        return new TokenEstimate(tokens, Method.HEURISTIC);
    }

    /**
     * 创建 usage 锚定估算。
     *
     * @param inputTokens provider 上报的输入 token
     * @return 估算值
     */
    public static TokenEstimate anchored(long inputTokens) {
        long safe = Math.max(0, inputTokens);
        int tokens = safe > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) safe;
        return new TokenEstimate(tokens, Method.USAGE_ANCHOR);
    }
}
