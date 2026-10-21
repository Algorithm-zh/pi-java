package com.piagent.domain.model.llm;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.time.Duration;
import java.util.Objects;

/**
 * 一次 LLM 调用的运行参数。
 *
 * <p>超时覆盖连接、等待响应与流式读取的总时长；取消不通过该对象表达，
 * 而是调用 {@link LlmStream#cancel()}。</p>
 *
 * @param timeout 总超时
 */
public record LlmCallContext(Duration timeout) {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);

    /**
     * 校验超时。
     *
     * @param timeout 总超时
     */
    public LlmCallContext {
        Objects.requireNonNull(timeout, "timeout 不能为空");
        Guard.require(!timeout.isZero() && !timeout.isNegative(), DomainErrorCode.INVALID_ARGUMENT,
                "timeout 必须大于 0");
    }

    /**
     * 默认两分钟超时。
     *
     * @return 默认上下文
     */
    public static LlmCallContext defaults() {
        return new LlmCallContext(DEFAULT_TIMEOUT);
    }
}
