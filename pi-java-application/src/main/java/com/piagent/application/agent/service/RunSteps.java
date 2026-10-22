package com.piagent.application.agent.service;

import com.piagent.domain.agent.AgentRun;
import com.piagent.domain.agent.RunId;
import com.piagent.domain.agent.RunStopReason;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.message.ToolCallBlock;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * run 内的“守卫检查”与“工具批次执行”。
 *
 * <p>为什么独立成类：这些逻辑都是“读 run 快照 → 判断 → 结束或执行”，与 Agent Loop 的
 * 消息流转无关；抽出来之后主循环只剩“守卫 → 压缩 → 模型 → 落库 → 工具”五步，更易读。</p>
 *
 * <p>终止语义：守卫触发与工具批次失败都直接结束 run（写 RUN_SUMMARY），并把结果告诉
 * 调用方，保证主循环不会在 run 已结束的情况下继续请求模型。</p>
 */
final class RunSteps {

    private final RunRuntime runtime;
    private final TurnPipeline pipeline;

    /**
     * 创建步骤集合。
     *
     * @param runtime 运行运行时
     * @param pipeline turn 流水线
     */
    RunSteps(RunRuntime runtime, TurnPipeline pipeline) {
        this.runtime = runtime;
        this.pipeline = pipeline;
    }

    /**
     * 检查取消、超时与轮数预算。
     *
     * @param context 运行上下文
     * @param run 当前 run 快照
     * @return true 表示 run 已结束，主循环应退出
     */
    boolean stopByGuard(RunContext context, AgentRun run) {
        if (runtime.isCancelled(run.id())) {
            runtime.finish(context.reserved(), RunStopReason.CANCELLED, null);
            return true;
        }
        if (run.budget().timedOut(run.startedAt(), runtime.now())) {
            runtime.finish(context.reserved(), RunStopReason.TIMEOUT, "运行超过最大时长");
            return true;
        }
        if (run.budget().turnsExhausted(run.turns())) {
            runtime.finish(context.reserved(), RunStopReason.MAX_STEPS, "达到最大模型轮数");
            return true;
        }
        return false;
    }

    /**
     * 执行一批工具调用。
     *
     * @param context 运行上下文
     * @param session 会话快照
     * @param calls 工具调用
     * @return true 表示可以继续下一轮；false 表示 run 已结束
     */
    boolean executeTools(RunContext context, ChatSession session, List<ToolCallBlock> calls) {
        RunId runId = context.reserved().runId();
        AgentRun run = runtime.current(runId);
        int remaining = Math.max(0, run.budget().maxToolCalls() - run.toolCalls());
        BooleanSupplier cancelled = () -> runtime.isCancelled(runId);
        ToolBatchResult batch = pipeline.executeTools(new ToolBatchContext(session, runId, calls,
                context.reserved().tools(), context.events(), cancelled, remaining));
        runtime.recordToolCalls(runId, batch.executed());
        if (batch.cancelled()) {
            runtime.finish(context.reserved(), RunStopReason.CANCELLED, null);
            return false;
        }
        if (batch.budgetExhausted()) {
            runtime.finish(context.reserved(), RunStopReason.MAX_STEPS, "达到最大工具调用次数");
            return false;
        }
        return true;
    }
}
