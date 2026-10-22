package com.piagent.domain.context;

import java.util.Objects;

/**
 * 一次自动压缩触发判定结果。
 *
 * @param trigger 是否触发
 * @param reason 判定原因（中文，可直接进日志与 SSE 事件）
 * @param tokens 判定时的上下文估算
 */
public record CompactionDecision(boolean trigger, String reason, int tokens) {

    /**
     * 校验判定。
     *
     * @param trigger 是否触发
     * @param reason 原因
     * @param tokens 上下文估算
     */
    public CompactionDecision {
        Objects.requireNonNull(reason, "reason 不能为空");
    }

    /**
     * 构造“需要压缩”的判定。
     *
     * @param reason 原因
     * @param tokens 上下文估算
     * @return 判定
     */
    public static CompactionDecision trigger(String reason, int tokens) {
        return new CompactionDecision(true, reason, tokens);
    }

    /**
     * 构造“无需压缩”的判定。
     *
     * @param reason 原因
     * @param tokens 上下文估算
     * @return 判定
     */
    public static CompactionDecision skip(String reason, int tokens) {
        return new CompactionDecision(false, reason, tokens);
    }
}
