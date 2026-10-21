package com.piagent.domain.model.llm;

import java.util.Iterator;
import java.util.Optional;

/**
 * 流式模型调用。
 *
 * <p>调用约定：</p>
 * <ul>
 *   <li>{@link #hasNext()} 驱动实际网络读取，可能阻塞；实现应响应
 *       {@link #cancel()} 并尽快返回 false。</li>
 *   <li>正常结束时，迭代会先产出 {@link DoneDelta}，随后 {@link #response()}
 *       返回完整响应。</li>
 *   <li>传输或协议错误通过 {@link #next()} 抛出 {@link LlmGatewayException}；
 *       错误不会伪装成 delta，也不会产生最终响应。</li>
 *   <li>{@link #cancel()} 与 {@link #close()} 幂等；取消后不得再回调任何资源。</li>
 * </ul>
 */
public interface LlmStream extends Iterator<LlmDelta>, AutoCloseable {

    /**
     * 是否还有增量。
     *
     * @return true 表示可以继续读取
     */
    @Override
    boolean hasNext();

    /**
     * 读取下一个增量。
     *
     * @return 增量
     * @throws LlmGatewayException 传输或协议错误
     */
    @Override
    LlmDelta next();

    /**
     * 正常结束后的完整响应。
     *
     * @return 响应；尚未结束、已取消或出错时为空
     */
    Optional<LlmResponse> response();

    /**
     * 是否已取消。
     *
     * @return true 表示已取消
     */
    boolean isCancelled();

    /**
     * 请求取消并释放底层资源。
     */
    void cancel();

    /**
     * 释放资源，不改变调用结果语义。
     */
    @Override
    void close();
}
