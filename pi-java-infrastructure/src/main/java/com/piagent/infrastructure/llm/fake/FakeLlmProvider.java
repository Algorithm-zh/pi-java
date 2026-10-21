package com.piagent.infrastructure.llm.fake;

import com.piagent.domain.model.ProviderType;
import com.piagent.domain.model.llm.LlmCallContext;
import com.piagent.domain.model.llm.LlmGatewayException;
import com.piagent.domain.model.llm.LlmRequest;
import com.piagent.domain.model.llm.LlmResponse;
import com.piagent.domain.model.llm.LlmStream;
import com.piagent.domain.model.llm.DoneDelta;
import com.piagent.domain.model.llm.LlmDelta;
import com.piagent.infrastructure.llm.LlmModelProvider;
import java.time.Duration;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 离线 Fake LLM provider。
 *
 * <p>按队列消费脚本回合；队列耗尽后退化为回声，保证 demo 和 E2E 测试
 * 永远有确定性输出。支持文本、工具调用、失败与取消，不访问网络。</p>
 */
public final class FakeLlmProvider implements LlmModelProvider {

    private final Queue<FakeTurn> scriptedTurns = new ConcurrentLinkedQueue<>();

    /**
     * 创建回声 provider。
     */
    public FakeLlmProvider() {
    }

    /**
     * 创建带脚本回合的 provider。
     *
     * @param turns 脚本回合，按调用顺序消费
     */
    public FakeLlmProvider(FakeTurn... turns) {
        for (FakeTurn turn : turns) {
            scriptedTurns.add(turn);
        }
    }

    @Override
    public boolean supports(ProviderType provider) {
        return provider == ProviderType.FAKE;
    }

    @Override
    public LlmStream stream(LlmRequest request, LlmCallContext context) {
        FakeTurn scripted = scriptedTurns.poll();
        return new FakeLlmStream(scripted == null ? FakeTurn.echo(request) : scripted);
    }

    /**
     * 单个脚本回合的流实现。
     *
     * <p>取消与关闭都只影响本流；脚本对象不可变，可安全复用。</p>
     */
    static final class FakeLlmStream implements LlmStream {

        private final FakeTurn turn;
        private int index;
        private boolean doneEmitted;
        private boolean failureThrown;
        private volatile boolean cancelled;
        private volatile boolean closed;
        private volatile LlmResponse response;

        FakeLlmStream(FakeTurn turn) {
            this.turn = turn;
        }

        @Override
        public boolean hasNext() {
            if (cancelled || closed) {
                return false;
            }
            if (index < turn.deltas().size()) {
                return true;
            }
            if (turn.failure() != null) {
                return !failureThrown;
            }
            return !doneEmitted;
        }

        @Override
        public LlmDelta next() {
            if (cancelled || closed) {
                throw new NoSuchElementException("流已关闭");
            }
            if (index < turn.deltas().size()) {
                sleep(turn.deltaDelay());
                if (cancelled) {
                    throw new NoSuchElementException("流已取消");
                }
                return turn.deltas().get(index++);
            }
            if (turn.failure() != null && !failureThrown) {
                failureThrown = true;
                throw new LlmGatewayException(turn.failure().code(), turn.failure().message());
            }
            if (turn.failure() == null && !doneEmitted) {
                doneEmitted = true;
                response = turn.response();
                return new DoneDelta(response.stopReason());
            }
            throw new NoSuchElementException("流已结束");
        }

        @Override
        public Optional<LlmResponse> response() {
            return Optional.ofNullable(response);
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        @Override
        public void close() {
            closed = true;
        }

        private void sleep(Duration delay) {
            long remaining = delay.toMillis();
            while (remaining > 0 && !cancelled) {
                long chunk = Math.min(remaining, 25);
                try {
                    Thread.sleep(chunk);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    cancelled = true;
                    return;
                }
                remaining -= chunk;
            }
        }
    }
}
