package com.piagent.application.agent.service;

import com.piagent.application.context.CompactionResult;
import com.piagent.domain.agent.RunId;
import com.piagent.domain.agent.RunStopReason;
import com.piagent.domain.model.llm.LlmErrorCode;
import com.piagent.domain.model.llm.LlmGatewayException;
import com.piagent.domain.model.llm.LlmRequest;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.ProjectedContext;
import org.springframework.stereotype.Component;

/**
 * 模型调用与上下文溢出重试。
 *
 * <p>把“发起一轮请求”和“溢出后压缩再试一次”封装成一个组件，让 Agent Loop 的主循环
 * 只表达状态机（守卫 → 调用 → 落库 → 工具 → 收尾），而不是把错误分支铺在主流程里。</p>
 *
 * <p>重试策略：provider 返回 {@link LlmErrorCode#CONTEXT_OVERFLOW} 时，先强制压缩一次；
 * 压缩成功则重试一次，仍然失败则结束 run。只重试一次，避免“压缩 → 还是溢出 → 再压缩”
 * 的无限循环。</p>
 */
@Component
public class TurnStreamer {

    private static final int MAX_ERROR_LENGTH = 4_000;

    private final TurnPipeline pipeline;
    private final RunCompactionStep compactionStep;
    private final RunRuntime runtime;

    /**
     * 创建调用器。
     *
     * @param pipeline turn 流水线
     * @param compactionStep 压缩步骤
     * @param runtime 运行运行时
     */
    public TurnStreamer(TurnPipeline pipeline, RunCompactionStep compactionStep,
            RunRuntime runtime) {
        this.pipeline = pipeline;
        this.compactionStep = compactionStep;
        this.runtime = runtime;
    }

    /**
     * 执行一轮模型调用。
     *
     * @param context 运行上下文
     * @param session 会话快照
     * @param turn 轮次（从 1 开始）
     * @return 流式结果；无法继续时返回 null（run 已收尾）
     */
    public TurnResult stream(RunContext context, ChatSession session, int turn) {
        try {
            return once(context, session, turn);
        } catch (LlmGatewayException error) {
            if (error.code() != LlmErrorCode.CONTEXT_OVERFLOW) {
                runtime.finish(context.reserved(), RunStopReason.ERROR,
                        "模型调用失败: " + message(error));
                return null;
            }
            return retryAfterOverflow(context, session, turn, error);
        }
    }

    private TurnResult retryAfterOverflow(RunContext context, ChatSession session, int turn,
            LlmGatewayException error) {
        boolean compacted = compactionStep.compactAfterOverflow(session,
                context.reserved().runId(), context.profile(), context.budget(),
                context.events()).filter(CompactionResult::applied).isPresent();
        if (!compacted) {
            runtime.finish(context.reserved(), RunStopReason.ERROR,
                    "上下文超出模型窗口且无法压缩: " + message(error));
            return null;
        }
        try {
            ChatSession refreshed = runtime.requireSession(context.sessionId());
            return once(context, refreshed, turn);
        } catch (LlmGatewayException retryError) {
            runtime.finish(context.reserved(), RunStopReason.ERROR,
                    "压缩后仍无法调用模型: " + message(retryError));
            return null;
        }
    }

    private TurnResult once(RunContext context, ChatSession session, int turn) {
        context.events().turnStarted(turn);
        ProjectedContext projected = pipeline.project(session);
        LlmRequest request = pipeline.request(session, context.profile(), projected,
                context.reserved().tools(), context.explicitSkill());
        RunId runId = context.reserved().runId();
        return pipeline.stream(request, runId, context.events(), () -> runtime.isCancelled(runId));
    }

    private String message(Throwable error) {
        String text = error.getMessage() == null
                ? error.getClass().getSimpleName() : error.getMessage();
        return text.length() <= MAX_ERROR_LENGTH ? text : text.substring(0, MAX_ERROR_LENGTH);
    }
}
