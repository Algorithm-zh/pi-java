package com.piagent.application.agent.service;

import com.piagent.domain.agent.RunEvent;
import com.piagent.domain.agent.RunEventType;
import com.piagent.domain.agent.RunId;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 进程内 run 事件缓冲与广播中心。
 *
 * <p>每个 run 保留最近 {@value #BUFFER_CAPACITY} 条事件，SSE 断线后按
 * {@code Last-Event-ID} 重放。事件序号在通道内单线程递增，保证同一 run 的
 * 事件顺序与缓冲顺序一致。事件只保存在内存，进程重启后重连会收到
 * {@code RUN_NOT_FOUND}，由前端重新加载会话。
 */
@Component
public class RunEventHub {

    /** 每个 run 保留的最大事件数。 */
    public static final int BUFFER_CAPACITY = 500;

    private static final Duration RETENTION = Duration.ofMinutes(30);
    private static final int MAX_CHANNELS = 300;
    private static final int EVICT_DOWN_TO = 200;

    private final Clock clock;
    private final ConcurrentMap<RunId, Channel> channels = new ConcurrentHashMap<>();

    /**
     * 创建事件中心。
     *
     * @param clock 时钟
     */
    public RunEventHub(Clock clock) {
        this.clock = clock;
    }

    /**
     * 为 run 建立事件通道。
     *
     * @param runId 运行标识
     * @param sessionId 会话标识
     */
    public void open(RunId runId, SessionId sessionId) {
        channels.put(runId, new Channel(runId, sessionId, clock.instant()));
        prune();
    }

    /**
     * 通道是否存在。
     *
     * @param runId 运行标识
     * @return true 表示存在
     */
    public boolean exists(RunId runId) {
        return channels.containsKey(runId);
    }

    /**
     * 通道是否仍可接收新事件。
     *
     * @param runId 运行标识
     * @return true 表示未关闭
     */
    public boolean isOpen(RunId runId) {
        Channel channel = channels.get(runId);
        return channel != null && channel.isOpen();
    }

    /**
     * 发布事件；通道已关闭或不存在时静默忽略。
     *
     * @param runId 运行标识
     * @param type 事件类型
     * @param data 事件数据
     */
    public void publish(RunId runId, RunEventType type, Map<String, Object> data) {
        Channel channel = channels.get(runId);
        if (channel != null) {
            channel.publish(type, data);
        }
    }

    /**
     * 订阅 run 事件并重放指定序号之后的事件。
     *
     * <p>重放与监听器注册在同一把锁内完成，避免重放期间插入的新事件乱序。
     * 若请求的序号已不在缓冲区内，会先收到一条 {@link RunEventType#REPLAY_GAP}。
     *
     * @param runId 运行标识
     * @param afterSeq 已收到的最大序号，0 表示从头重放
     * @param listener 事件消费者，在发布线程上同步调用
     * @return 订阅句柄
     */
    public RunSubscription subscribe(RunId runId, long afterSeq, Consumer<RunEvent> listener) {
        if (afterSeq < 0) {
            throw new DomainException(DomainErrorCode.INVALID_ARGUMENT, "afterSeq 不能为负数");
        }
        Channel channel = channels.get(runId);
        if (channel == null) {
            throw new DomainException(DomainErrorCode.RUN_NOT_FOUND,
                    "run 事件不存在或已被清理: " + runId);
        }
        return channel.subscribe(afterSeq, listener);
    }

    /**
     * 关闭通道，保留缓冲供后续订阅者重放。
     *
     * @param runId 运行标识
     */
    public void close(RunId runId) {
        Channel channel = channels.get(runId);
        if (channel != null) {
            channel.close(clock.instant());
        }
    }

    /**
     * 当前缓冲事件数，供测试与诊断使用。
     *
     * @param runId 运行标识
     * @return 事件数；通道不存在时为 0
     */
    public int buffered(RunId runId) {
        Channel channel = channels.get(runId);
        return channel == null ? 0 : channel.buffered();
    }

    /**
     * 当前已发布的最大序号。
     *
     * @param runId 运行标识
     * @return 最大序号；通道不存在时为 0
     */
    public long lastSeq(RunId runId) {
        Channel channel = channels.get(runId);
        return channel == null ? 0 : channel.lastSeq();
    }

    private void prune() {
        Instant cutoff = clock.instant().minus(RETENTION);
        channels.entrySet().removeIf(entry -> entry.getValue().isEvictable(cutoff));
        if (channels.size() <= MAX_CHANNELS) {
            return;
        }
        List<Map.Entry<RunId, Channel>> closed = channels.entrySet().stream()
                .filter(entry -> entry.getValue().isClosed())
                .sorted(Comparator.comparing(
                        (Map.Entry<RunId, Channel> entry) -> entry.getValue().touchedAt()))
                .collect(Collectors.toList());
        for (Map.Entry<RunId, Channel> entry : closed) {
            if (channels.size() <= EVICT_DOWN_TO) {
                break;
            }
            channels.remove(entry.getKey(), entry.getValue());
        }
    }

    /**
     * 单个 run 的事件通道。
     */
    private final class Channel {

        private final RunId runId;
        private final SessionId sessionId;
        private final ArrayDeque<RunEvent> buffer = new ArrayDeque<>();
        private final CopyOnWriteArrayList<Consumer<RunEvent>> listeners = new CopyOnWriteArrayList<>();
        private long nextSeq = 1;
        private boolean closed;
        private Instant touchedAt;

        private Channel(RunId runId, SessionId sessionId, Instant openedAt) {
            this.runId = runId;
            this.sessionId = sessionId;
            this.touchedAt = openedAt;
        }

        private synchronized void publish(RunEventType type, Map<String, Object> data) {
            if (closed) {
                return;
            }
            RunEvent event = new RunEvent(nextSeq++, runId, sessionId, type, clock.instant(), data);
            buffer.addLast(event);
            if (buffer.size() > BUFFER_CAPACITY) {
                buffer.removeFirst();
            }
            touchedAt = event.at();
            for (Consumer<RunEvent> listener : listeners) {
                try {
                    listener.accept(event);
                } catch (RuntimeException ignored) {
                    // 单个订阅者失败不能阻断 run；SSE 层会自行关闭失效连接。
                }
            }
        }

        private synchronized RunSubscription subscribe(long afterSeq, Consumer<RunEvent> listener) {
            long published = nextSeq - 1;
            // afterSeq=0 表示从头订阅；如果缓冲区已经淘汰了早期事件，同样要报缺口。
            boolean gap = published > afterSeq && !buffer.isEmpty()
                    && buffer.getFirst().seq() > afterSeq + 1;
            if (gap) {
                long earliest = buffer.getFirst().seq();
                listener.accept(new RunEvent(afterSeq, runId, sessionId,
                        RunEventType.REPLAY_GAP, clock.instant(),
                        Map.of("requestedSeq", afterSeq, "earliestSeq", earliest)));
            }
            for (RunEvent event : buffer) {
                if (event.seq() > afterSeq) {
                    listener.accept(event);
                }
            }
            if (!closed) {
                listeners.add(listener);
            }
            touchedAt = clock.instant();
            return new RunSubscription(closed, () -> listeners.remove(listener));
        }

        private synchronized void close(Instant at) {
            closed = true;
            touchedAt = at;
        }

        private synchronized boolean isOpen() {
            return !closed;
        }

        private synchronized boolean isClosed() {
            return closed;
        }

        private synchronized boolean isEvictable(Instant cutoff) {
            return closed && listeners.isEmpty() && touchedAt.isBefore(cutoff);
        }

        private synchronized Instant touchedAt() {
            return touchedAt;
        }

        private synchronized int buffered() {
            return buffer.size();
        }

        private synchronized long lastSeq() {
            return nextSeq - 1;
        }
    }
}
