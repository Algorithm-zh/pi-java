package com.piagent.trigger.http.run;

import com.piagent.domain.agent.RunEvent;
import com.piagent.domain.agent.RunEventType;
import com.piagent.domain.agent.RunId;
import com.piagent.application.agent.service.RunEventHub;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 单个 SSE 连接的生命周期。
 *
 * <p>订阅在 {@link #start()} 中同步完成：先重放缓冲，再注册监听器，避免丢失
 * 重放与实时之间的事件。收到终态事件后异步完成 emitter，保证重连到已结束的
 * run 时也能先返回 emitter 再结束响应。断连只移除监听器，不取消 run。</p>
 */
final class RunSseChannel {

    private static final long HEARTBEAT_MILLIS = 15_000L;

    private final RunId runId;
    private final long afterSeq;
    private final RunEventHub hub;
    private final SseEmitter emitter;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<AutoCloseable> subscription = new AtomicReference<>();
    private final AtomicReference<ScheduledFuture<?>> heartbeat = new AtomicReference<>();

    RunSseChannel(RunId runId, long afterSeq, RunEventHub hub, SseEmitter emitter,
            ScheduledExecutorService scheduler) {
        this.runId = runId;
        this.afterSeq = afterSeq;
        this.hub = hub;
        this.emitter = emitter;
        this.scheduler = scheduler;
    }

    /**
     * 建立订阅并启动心跳。
     */
    void start() {
        emitter.onCompletion(this::close);
        emitter.onTimeout(this::close);
        emitter.onError(error -> close());
        AutoCloseable created = hub.subscribe(runId, afterSeq, this::onEvent);
        subscription.set(created);
        if (closed.get()) {
            closeQuietly(created);
            return;
        }
        heartbeat.set(scheduler.scheduleAtFixedRate(this::heartbeat,
                HEARTBEAT_MILLIS, HEARTBEAT_MILLIS, TimeUnit.MILLISECONDS));
    }

    private void onEvent(RunEvent event) {
        if (closed.get()) {
            return;
        }
        try {
            synchronized (emitter) {
                emitter.send(SseEmitter.event()
                        .id(runId + ":" + event.seq())
                        .name(event.type().wireName())
                        .data(envelope(event)));
            }
        } catch (IOException | IllegalStateException error) {
            close();
            return;
        }
        if (event.type().isTerminal()) {
            scheduler.execute(this::complete);
        }
    }

    private void heartbeat() {
        if (closed.get()) {
            return;
        }
        try {
            synchronized (emitter) {
                emitter.send(SseEmitter.event().name(RunEventType.HEARTBEAT.wireName())
                        .data(Map.of("at", Instant.now().toString())));
            }
        } catch (IOException | IllegalStateException error) {
            close();
        }
    }

    private Map<String, Object> envelope(RunEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("seq", event.seq());
        data.put("runId", event.runId().toString());
        data.put("sessionId", event.sessionId().toString());
        data.put("type", event.type().wireName());
        data.put("at", event.at().toString());
        data.put("data", event.data());
        return data;
    }

    private void complete() {
        if (closed.compareAndSet(false, true)) {
            releaseResources();
            emitter.complete();
        }
    }

    private void close() {
        if (closed.compareAndSet(false, true)) {
            releaseResources();
        }
    }

    private void releaseResources() {
        closeQuietly(subscription.getAndSet(null));
        ScheduledFuture<?> current = heartbeat.getAndSet(null);
        if (current != null) {
            current.cancel(false);
        }
    }

    private void closeQuietly(AutoCloseable resource) {
        if (resource != null) {
            try {
                resource.close();
            } catch (Exception ignored) {
                // 关闭订阅不抛受检异常；吞掉实现细节，避免影响 SSE 收尾。
            }
        }
    }
}
