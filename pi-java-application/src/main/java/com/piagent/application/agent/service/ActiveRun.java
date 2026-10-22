package com.piagent.application.agent.service;

import com.piagent.domain.agent.AgentRun;
import com.piagent.domain.model.llm.LlmStream;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 进程内的活跃 run 槽位。
 *
 * <p>除了不可变快照外，还持有取消标记、当前 LLM 流与结束闩锁。取消标记是
 * 跨线程广播的唯一信号；LLM 流引用用于把取消立即传导到网络读取。
 */
final class ActiveRun {

    private final AtomicReference<AgentRun> state;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicReference<LlmStream> stream = new AtomicReference<>();
    private final CountDownLatch done = new CountDownLatch(1);

    ActiveRun(AgentRun run) {
        this.state = new AtomicReference<>(run);
    }

    AgentRun state() {
        return state.get();
    }

    void state(AgentRun updated) {
        state.set(updated);
    }

    boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * 标记取消并中断当前 LLM 流；重复调用幂等。
     */
    void cancel() {
        cancelled.set(true);
        LlmStream current = stream.get();
        if (current != null) {
            current.cancel();
        }
    }

    /**
     * 绑定当前 LLM 流；若在绑定前已取消，立即取消新流。
     *
     * @param next 新流
     */
    void attach(LlmStream next) {
        stream.set(next);
        if (cancelled.get()) {
            next.cancel();
        }
    }

    void clear(LlmStream expected) {
        stream.compareAndSet(expected, null);
    }

    void countDown() {
        done.countDown();
    }

    boolean await(Duration timeout) throws InterruptedException {
        return done.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }
}
