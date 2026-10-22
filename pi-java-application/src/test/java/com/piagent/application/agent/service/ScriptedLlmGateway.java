package com.piagent.application.agent.service;

import com.piagent.domain.model.Usage;
import com.piagent.domain.model.llm.DoneDelta;
import com.piagent.domain.model.llm.LlmCallContext;
import com.piagent.domain.model.llm.LlmDelta;
import com.piagent.domain.model.llm.LlmGateway;
import com.piagent.domain.model.llm.LlmGatewayException;
import com.piagent.domain.model.llm.LlmRequest;
import com.piagent.domain.model.llm.LlmResponse;
import com.piagent.domain.model.llm.LlmStream;
import com.piagent.domain.model.llm.TextDelta;
import com.piagent.domain.model.llm.ToolCallDelta;
import com.piagent.domain.session.model.message.AssistantStopReason;
import com.piagent.domain.session.model.message.TextBlock;
import com.piagent.domain.session.model.message.ToolCallBlock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * 按队列消费脚本流的 LLM 网关测试替身。
 *
 * <p>每次 {@code stream} 调用记录请求并弹出一个脚本；脚本耗尽视为测试编排错误，
 * 以网关异常暴露，避免 Loop 静默跑出不确定结果。</p>
 */
final class ScriptedLlmGateway implements LlmGateway {

    private final Deque<LlmStream> scripted = new ArrayDeque<>();
    private final List<LlmRequest> requests = new ArrayList<>();

    void enqueue(LlmStream stream) {
        scripted.addLast(stream);
    }

    List<LlmRequest> requests() {
        return List.copyOf(requests);
    }

    @Override
    public LlmStream stream(LlmRequest request, LlmCallContext context) {
        requests.add(request);
        LlmStream next = scripted.pollFirst();
        if (next == null) {
            throw new LlmGatewayException(
                    com.piagent.domain.model.llm.LlmErrorCode.SERVER_ERROR, "测试脚本已耗尽");
        }
        return next;
    }

    /**
     * 纯文本脚本。
     *
     * @param text 文本
     * @return 流
     */
    static LlmStream text(String text) {
        return text(text, null);
    }

    /**
     * 纯文本脚本，并在产出首个 delta 后执行回调。
     *
     * <p>回调用于在同一线程内触发取消，模拟用户在流式输出过程中点击停止。</p>
     *
     * @param text 文本
     * @param onFirstRead 首个 delta 产出后的回调
     * @return 流
     */
    static LlmStream text(String text, Runnable onFirstRead) {
        LlmResponse response = new LlmResponse(List.of(new TextBlock(text)), Usage.zero(),
                AssistantStopReason.STOP, null);
        return new ScriptedStream(List.of(new TextDelta(text)), response, null, onFirstRead);
    }

    /**
     * 工具调用脚本。
     *
     * @param calls 工具调用
     * @return 流
     */
    static LlmStream toolCalls(List<ToolCallBlock> calls) {
        List<LlmDelta> deltas = new ArrayList<>();
        for (ToolCallBlock call : calls) {
            deltas.add(new ToolCallDelta(0, call.id(), call.name(), "{}"));
        }
        LlmResponse response = new LlmResponse(List.copyOf(calls), Usage.zero(),
                AssistantStopReason.TOOL_USE, null);
        return new ScriptedStream(deltas, response, null, null);
    }

    /**
     * 立即失败的脚本。
     *
     * @param error 网关异常
     * @return 流
     */
    static LlmStream failure(LlmGatewayException error) {
        return new ScriptedStream(List.of(), null, error, null);
    }

    private static final class ScriptedStream implements LlmStream {

        private final List<LlmDelta> deltas;
        private final LlmResponse response;
        private final LlmGatewayException failure;
        private final Runnable onFirstRead;
        private int index;
        private boolean failureThrown;
        private boolean doneEmitted;
        private boolean cancelled;
        private boolean closed;

        private ScriptedStream(List<LlmDelta> deltas, LlmResponse response,
                LlmGatewayException failure, Runnable onFirstRead) {
            this.deltas = deltas;
            this.response = response;
            this.failure = failure;
            this.onFirstRead = onFirstRead;
        }

        @Override
        public boolean hasNext() {
            if (cancelled || closed) {
                return false;
            }
            if (index < deltas.size()) {
                return true;
            }
            return failure != null ? !failureThrown : !doneEmitted;
        }

        @Override
        public LlmDelta next() {
            if (!hasNext()) {
                throw new NoSuchElementException("脚本流已结束");
            }
            if (index < deltas.size()) {
                LlmDelta delta = deltas.get(index++);
                if (index == 1 && onFirstRead != null) {
                    onFirstRead.run();
                }
                return delta;
            }
            if (failure != null) {
                failureThrown = true;
                throw failure;
            }
            doneEmitted = true;
            return new DoneDelta(response.stopReason());
        }

        @Override
        public Optional<LlmResponse> response() {
            return doneEmitted ? Optional.ofNullable(response) : Optional.empty();
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
    }
}
