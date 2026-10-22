package com.piagent.application.agent.service;

/**
 * run 事件订阅句柄。
 *
 * <p>关闭订阅只移除监听器，不影响 run 本身；SSE 客户端断开必须关闭订阅，
 * 避免缓冲区和监听器泄漏。</p>
 */
public final class RunSubscription implements AutoCloseable {

    private final boolean closedAtSubscribe;
    private final Runnable onClose;

    RunSubscription(boolean closedAtSubscribe, Runnable onClose) {
        this.closedAtSubscribe = closedAtSubscribe;
        this.onClose = onClose;
    }

    /**
     * 订阅建立时事件通道是否已关闭。
     *
     * <p>为 true 时调用方在完成重放后应立即结束 SSE，避免永远等待不会到来的事件。</p>
     *
     * @return true 表示 run 已结束且不会再有新事件
     */
    public boolean closedAtSubscribe() {
        return closedAtSubscribe;
    }

    @Override
    public void close() {
        onClose.run();
    }
}
