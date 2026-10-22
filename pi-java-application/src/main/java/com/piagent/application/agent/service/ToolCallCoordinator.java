package com.piagent.application.agent.service;

import com.piagent.domain.agent.RunId;
import com.piagent.domain.governance.model.ApprovalContext;
import com.piagent.domain.governance.model.ApprovalRequest;
import com.piagent.domain.governance.model.ApprovalStatus;
import com.piagent.domain.governance.model.SandboxPolicy;
import com.piagent.domain.governance.service.ApprovalArgumentsSanitizer;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.message.ToolCallBlock;
import com.piagent.domain.tool.model.ToolCallId;
import com.piagent.domain.tool.model.ToolInvocation;
import com.piagent.domain.tool.model.ToolName;
import com.piagent.domain.tool.model.ToolResult;
import com.piagent.domain.tool.port.ToolInvoker;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 工具调用协调器：执行预检计划、处理审批并回写结果。
 *
 * <p>关键约束：</p>
 * <ul>
 *   <li>每个 toolCall 必须得到一条 toolResult，即使策略拒绝、预算不足、运行取消或审批未通过；</li>
 *   <li>只有整批调用都无需审批且都可并行时才并行；混入任一串行工具或审批调用则整批按模型给出的顺序执行；</li>
 *   <li>请求与结果按调用顺序落库，并行执行不改变会话日志顺序；</li>
 *   <li>{@code ASK} 调用创建审批待办并阻塞等待终态，批准后才真正执行工具。</li>
 * </ul>
 *
 * <p>策略解析与预算裁剪由 {@link ToolCallPreflight} 完成，本类只消费其产出的
 * 执行计划，因此不直接依赖策略仓储。</p>
 */
@Component
public class ToolCallCoordinator {

    private final ToolInvoker invoker;
    private final SessionEntryAppender appender;
    private final ApprovalGate approvalGate;
    private final ToolCallPreflight preflight;
    private final ExecutorService executor;

    /**
     * 创建协调器。
     *
     * @param invoker 工具执行器
     * @param appender entry 追加器
     * @param approvalGate 审批门面
     * @param preflight 工具调用预检器
     * @param executor 工具并行执行器
     */
    public ToolCallCoordinator(ToolInvoker invoker, SessionEntryAppender appender,
            ApprovalGate approvalGate, ToolCallPreflight preflight,
            @Qualifier("toolExecutor") ExecutorService executor) {
        this.invoker = invoker;
        this.appender = appender;
        this.approvalGate = approvalGate;
        this.preflight = preflight;
        this.executor = executor;
    }

    /**
     * 执行一批工具调用并写入会话日志。
     *
     * @param context 批次上下文
     * @return 批次摘要
     */
    public ToolBatchResult execute(ToolBatchContext context) {
        List<PreparedCall> prepared = preflight.prepare(context.session(), context.calls(),
                context.registry(), context.remainingCalls());
        SandboxPolicy sandbox = sandboxOf(context.session());
        BatchExecution execution = preflight.supportsParallel(prepared)
                ? executeParallel(prepared, sandbox, context.events(), context.cancelled())
                : executeSequential(context.session(), context.runId(), prepared, sandbox,
                        context.events(), context.cancelled());
        writeResults(context.session(), execution.results());
        boolean budgetExhausted = prepared.stream().anyMatch(PreparedCall::budgetBlocked);
        return new ToolBatchResult(execution.executed(), context.cancelled().getAsBoolean(),
                budgetExhausted);
    }

    private BatchExecution executeSequential(ChatSession session, RunId runId,
            List<PreparedCall> prepared, SandboxPolicy sandbox, RunEventPublisher events,
            BooleanSupplier cancelled) {
        List<ToolResult> results = new ArrayList<>(prepared.size());
        int executed = 0;
        for (PreparedCall call : prepared) {
            events.toolStarted(call.call());
            ToolResult result;
            if (!call.requiresExecution()) {
                result = call.rejection();
            } else if (cancelled.getAsBoolean()) {
                result = cancelled(call.call());
            } else if (call.approvalRequired()) {
                ApprovalExecution approval = executeWithApproval(session, runId, call, sandbox,
                        cancelled);
                result = approval.result();
                if (approval.invoked()) {
                    executed++;
                }
            } else {
                result = invoke(call, sandbox);
                executed++;
            }
            results.add(result);
            events.toolCompleted(result);
        }
        return new BatchExecution(results, executed);
    }

    private ApprovalExecution executeWithApproval(ChatSession session, RunId runId,
            PreparedCall call, SandboxPolicy sandbox, BooleanSupplier cancelled) {
        ApprovalContext context = new ApprovalContext(session.identity().id(), runId,
                callIdOf(call.call()), call.descriptor().name(),
                ApprovalArgumentsSanitizer.sanitize(call.call().arguments()),
                call.descriptor().risk());
        ApprovalRequest approval;
        try {
            approval = approvalGate.await(context, cancelled);
        } catch (RuntimeException error) {
            return new ApprovalExecution(ToolResult.error(callIdOf(call.call()),
                    call.descriptor().name(), "等待审批失败: " + message(error)), false);
        }
        if (approval.status() != ApprovalStatus.APPROVED) {
            return new ApprovalExecution(approvalRejection(call, approval), false);
        }
        if (cancelled.getAsBoolean()) {
            return new ApprovalExecution(cancelled(call.call()), false);
        }
        return new ApprovalExecution(invoke(call, sandbox), true);
    }

    private ToolResult approvalRejection(PreparedCall call, ApprovalRequest approval) {
        String name = call.descriptor().name().toString();
        String message = switch (approval.status()) {
            case DENIED -> "用户拒绝执行工具 " + name
                    + (approval.reason() == null ? "" : ": " + approval.reason());
            case EXPIRED -> "工具 " + name + " 的审批已过期，未执行";
            case CANCELLED -> "运行已取消，工具 " + name + " 未执行";
            default -> "工具 " + name + " 的审批未通过";
        };
        return ToolResult.error(callIdOf(call.call()), call.descriptor().name(), message);
    }

    private BatchExecution executeParallel(List<PreparedCall> prepared, SandboxPolicy sandbox,
            RunEventPublisher events, BooleanSupplier cancelled) {
        List<Future<ToolResult>> futures = new ArrayList<>(prepared.size());
        int executed = 0;
        for (PreparedCall call : prepared) {
            events.toolStarted(call.call());
            if (!call.executable()) {
                futures.add(CompletableFuture.completedFuture(call.rejection()));
            } else if (cancelled.getAsBoolean()) {
                futures.add(CompletableFuture.completedFuture(cancelled(call.call())));
            } else {
                futures.add(executor.submit(() -> invoke(call, sandbox)));
                executed++;
            }
        }
        List<ToolResult> results = new ArrayList<>(prepared.size());
        for (int index = 0; index < futures.size(); index++) {
            ToolResult result = await(prepared.get(index).call(), futures.get(index));
            results.add(result);
            events.toolCompleted(result);
        }
        return new BatchExecution(results, executed);
    }

    private ToolResult await(ToolCallBlock call, Future<ToolResult> future) {
        try {
            return future.get();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return ToolResult.error(callIdOf(call), toolNameOf(call), "等待工具执行时被中断");
        } catch (ExecutionException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            return ToolResult.error(callIdOf(call), toolNameOf(call), "工具执行异常: " + cause.getMessage());
        }
    }

    private ToolResult invoke(PreparedCall call, SandboxPolicy sandbox) {
        ToolCallId callId = callIdOf(call.call());
        try {
            ToolInvocation invocation = new ToolInvocation(callId, call.descriptor().name(),
                    call.call().arguments(), sandbox);
            return invoker.invoke(invocation);
        } catch (RuntimeException error) {
            return ToolResult.error(callId, call.descriptor().name(),
                    "工具执行异常: " + error.getMessage());
        }
    }

    private void writeResults(ChatSession session, List<ToolResult> results) {
        appender.appendToolResults(session.identity().id(), results);
    }

    private SandboxPolicy sandboxOf(ChatSession session) {
        return new SandboxPolicy(session.config().cwd(),
                session.config().sandbox().writableRoots(), false);
    }

    private ToolResult cancelled(ToolCallBlock call) {
        return ToolResult.error(callIdOf(call), toolNameOf(call), "运行已取消，该工具调用未执行");
    }

    private ToolCallId callIdOf(ToolCallBlock call) {
        return new ToolCallId(call.id());
    }

    private ToolName toolNameOf(ToolCallBlock call) {
        return new ToolName(call.name());
    }

    private String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    /**
     * 一批工具的执行结果与真实执行数。
     *
     * @param results 按调用顺序排列的结果
     * @param executed 实际执行的调用数
     */
    private record BatchExecution(List<ToolResult> results, int executed) {
    }

    /**
     * 审批之后的执行结果。
     *
     * @param result 返回给模型的结果
     * @param invoked 是否真正调用了工具（审批拒绝、过期或取消时为 false）
     */
    private record ApprovalExecution(ToolResult result, boolean invoked) {
    }
}
