package com.piagent.application.agent.service;

import com.piagent.domain.agent.RunId;
import com.piagent.domain.model.llm.DoneDelta;
import com.piagent.domain.model.llm.LlmCallContext;
import com.piagent.domain.model.llm.LlmDelta;
import com.piagent.domain.model.llm.LlmGateway;
import com.piagent.domain.model.llm.LlmRequest;
import com.piagent.domain.model.llm.LlmResponse;
import com.piagent.domain.model.llm.LlmStream;
import com.piagent.domain.model.llm.TextDelta;
import com.piagent.domain.model.llm.ThinkingDelta;
import com.piagent.domain.model.llm.ToolCallDelta;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Component;

/**
 * 单轮 LLM 流式执行器。
 *
 * <p>只负责读取流、发布增量与把取消信号传导到 {@link LlmStream}；
 * 消息落库、工具执行与状态机都由调用方处理。流错误不在这里吞掉，
 * 由 Agent Loop 统一把 run 收尾为 FAILED。
 */
@Component
public class TurnExecutor {

    private static final System.Logger LOG = System.getLogger(TurnExecutor.class.getName());

    private final LlmGateway gateway;
    private final ActiveRunRegistry registry;

    /**
     * 创建执行器。
     *
     * @param gateway LLM 网关
     * @param registry run 注册表
     */
    public TurnExecutor(LlmGateway gateway, ActiveRunRegistry registry) {
        this.gateway = gateway;
        this.registry = registry;
    }

    /**
     * 执行一次模型请求。
     *
     * @param request 请求
     * @param runId 运行标识
     * @param events 事件发布器
     * @param cancelled 取消查询
     * @return 流式结果
     */
    public TurnResult execute(LlmRequest request, RunId runId, RunEventPublisher events,
            BooleanSupplier cancelled) {
        LlmStream stream = gateway.stream(request, LlmCallContext.defaults());
        registry.attachStream(runId, stream);
        StringBuilder text = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        try {
            while (!cancelled.getAsBoolean() && stream.hasNext()) {
                LlmDelta delta = stream.next();
                consume(delta, text, thinking, events);
                if (delta instanceof DoneDelta) {
                    break;
                }
            }
            if (cancelled.getAsBoolean() || stream.isCancelled()) {
                stream.cancel();
                return TurnResult.cancelled(text.toString(), thinking.toString());
            }
            LlmResponse response = stream.response()
                    .orElseThrow(() -> new DomainException(DomainErrorCode.INVALID_STATE_TRANSITION,
                            "模型流结束但没有最终响应"));
            return TurnResult.completed(response, text.toString(), thinking.toString());
        } finally {
            registry.clearStream(runId, stream);
            closeQuietly(stream);
        }
    }

    /**
     * 关闭流，并吞掉关闭阶段自身的异常。
     *
     * <p>为什么需要显式吞掉：{@code finally} 块里抛出的异常会覆盖 try 中正在传播的
     * 业务异常（例如 {@code LlmGatewayException(CONTEXT_OVERFLOW)}），导致上层无法识别
     * 溢出去触发压缩。关闭失败本身不影响已经确定的结果，只记录日志。</p>
     */
    private void closeQuietly(LlmStream stream) {
        try {
            stream.close();
        } catch (RuntimeException error) {
            LOG.log(System.Logger.Level.DEBUG, "关闭模型流失败（已忽略）", error);
        }
    }

    private void consume(LlmDelta delta, StringBuilder text, StringBuilder thinking,
            RunEventPublisher events) {
        switch (delta) {
            case TextDelta value -> {
                text.append(value.text());
                events.textDelta(value.text());
            }
            case ThinkingDelta value -> {
                thinking.append(value.text());
                events.thinkingDelta(value.text());
            }
            case ToolCallDelta value -> events.toolCallDelta(value);
            case DoneDelta ignored -> {
                // 结束标记只用于告诉迭代器可以取 response()，没有额外事件。
            }
        }
    }
}
