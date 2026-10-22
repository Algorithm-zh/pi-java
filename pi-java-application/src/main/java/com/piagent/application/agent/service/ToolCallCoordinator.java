package com.piagent.application.agent.service;

import com.piagent.domain.governance.model.PolicyEffect;
import com.piagent.domain.governance.model.SandboxPolicy;
import com.piagent.domain.governance.model.ToolPolicyRule;
import com.piagent.domain.governance.model.WorkspaceKey;
import com.piagent.domain.governance.port.ToolPolicyRepository;
import com.piagent.domain.governance.service.ToolAccessPolicy;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.message.ToolCallBlock;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.tool.model.ExecutionMode;
import com.piagent.domain.tool.model.ToolCallId;
import com.piagent.domain.tool.model.ToolDescriptor;
import com.piagent.domain.tool.model.ToolInvocation;
import com.piagent.domain.tool.model.ToolName;
import com.piagent.domain.tool.model.ToolResult;
import com.piagent.domain.tool.port.ToolInvoker;
import com.piagent.domain.tool.port.ToolRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 工具调用协调器：预检、分批、执行与结果回写。
 *
 * <p>关键约束：</p>
 * <ul>
 *   <li>每个 toolCall 必须得到一条 toolResult，即使策略拒绝、预算不足或运行取消；</li>
 *   <li>只有整批调用都可并行时才并行；混入任一串行工具则整批按模型给出的顺序执行；</li>
 *   <li>请求与结果按调用顺序落库，并行执行不改变会话日志顺序；</li>
 *   <li>M4 尚未实现审批，{@code ASK} 一律转为错误结果；M6 会在这里接入审批等待。</li>
 * </ul>
 */
@Component
public class ToolCallCoordinator {

    private static final ToolName UNKNOWN_TOOL = new ToolName("unknown");

    private final ToolRegistry registry;
    private final ToolInvoker invoker;
    private final ToolPolicyRepository policyRepository;
    private final ToolAccessPolicy accessPolicy;
    private final SessionEntryAppender appender;
    private final ExecutorService executor;

    /**
     * 创建协调器。
     *
     * @param registry 工具目录
     * @param invoker 工具执行器
     * @param policyRepository 策略仓储
     * @param accessPolicy 策略解析器
     * @param appender entry 追加器
     * @param executor 工具并行执行器
     */
    public ToolCallCoordinator(ToolRegistry registry, ToolInvoker invoker,
            ToolPolicyRepository policyRepository, ToolAccessPolicy accessPolicy,
            SessionEntryAppender appender,
            @Qualifier("toolExecutor") ExecutorService executor) {
        this.registry = registry;
        this.invoker = invoker;
        this.policyRepository = policyRepository;
        this.accessPolicy = accessPolicy;
        this.appender = appender;
        this.executor = executor;
    }

    /**
     * 执行一批工具调用并写入会话日志。
     *
     * @param session 当前会话
     * @param calls 模型返回的工具调用
     * @param events 事件发布器
     * @param cancelled 取消查询
     * @param remainingCalls 剩余可执行工具次数
     * @return 批次摘要
     */
    public ToolBatchResult execute(ChatSession session, List<ToolCallBlock> calls,
            RunEventPublisher events, BooleanSupplier cancelled, int remainingCalls) {
        List<ToolPolicyRule> rules = policyRepository.findByWorkspace(
                WorkspaceKey.of(session.config().cwd()));
        SandboxPolicy sandbox = sandboxOf(session);
        List<PreparedCall> prepared = prepare(calls, rules, remainingCalls);
        BatchExecution execution = supportsParallel(prepared)
                ? executeParallel(prepared, sandbox, events, cancelled)
                : executeSequential(prepared, sandbox, events, cancelled);
        writeResults(session, execution.results());
        boolean budgetExhausted = prepared.stream().anyMatch(PreparedCall::budgetBlocked);
        return new ToolBatchResult(execution.executed(), cancelled.getAsBoolean(), budgetExhausted);
    }

    private List<PreparedCall> prepare(List<ToolCallBlock> calls, List<ToolPolicyRule> rules,
            int remainingCalls) {
        List<PreparedCall> prepared = new ArrayList<>(calls.size());
        int granted = 0;
        for (ToolCallBlock call : calls) {
            PreparedCall preflight = preflight(call, rules);
            if (preflight.executable() && granted >= remainingCalls) {
                preflight = preflight.withRejection(ToolResult.error(safeId(call), safeName(call),
                        "已达到本次运行的工具调用上限，该调用未执行"), true);
            } else if (preflight.executable()) {
                granted++;
            }
            prepared.add(preflight);
        }
        return prepared;
    }

    private PreparedCall preflight(ToolCallBlock call, List<ToolPolicyRule> rules) {
        ToolName name = safeName(call);
        Optional<ToolDescriptor> found = registry.find(name);
        if (found.isEmpty()) {
            return new PreparedCall(call, null, ToolResult.error(safeId(call), UNKNOWN_TOOL,
                    "工具未注册: " + call.name()), false);
        }
        ToolDescriptor descriptor = found.get();
        PolicyEffect effect = accessPolicy.resolve(descriptor, rules);
        return switch (effect) {
            case ALLOW -> new PreparedCall(call, descriptor, null, false);
            case ASK -> new PreparedCall(call, descriptor, ToolResult.error(safeId(call), name,
                    "工具 " + name + " 需要用户审批；审批功能将在 M6 提供，本次调用未执行"), false);
            case DENY -> new PreparedCall(call, descriptor, ToolResult.error(safeId(call), name,
                    "工具策略拒绝执行: " + name), false);
        };
    }

    private boolean supportsParallel(List<PreparedCall> prepared) {
        List<PreparedCall> executable = prepared.stream().filter(PreparedCall::executable).toList();
        return executable.size() > 1 && executable.stream()
                .allMatch(call -> call.descriptor().executionMode() == ExecutionMode.PARALLEL);
    }

    private BatchExecution executeSequential(List<PreparedCall> prepared, SandboxPolicy sandbox,
            RunEventPublisher events, BooleanSupplier cancelled) {
        List<ToolResult> results = new ArrayList<>(prepared.size());
        int executed = 0;
        for (PreparedCall call : prepared) {
            events.toolStarted(call.call());
            ToolResult result;
            if (!call.executable()) {
                result = call.rejection();
            } else if (cancelled.getAsBoolean()) {
                result = cancelled(call.call());
            } else {
                result = invoke(call, sandbox);
                executed++;
            }
            results.add(result);
            events.toolCompleted(result);
        }
        return new BatchExecution(results, executed);
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
            return ToolResult.error(safeId(call), safeName(call), "等待工具执行时被中断");
        } catch (ExecutionException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            return ToolResult.error(safeId(call), safeName(call), "工具执行异常: " + cause.getMessage());
        }
    }

    private ToolResult invoke(PreparedCall call, SandboxPolicy sandbox) {
        ToolCallId callId = safeId(call.call());
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
        return ToolResult.error(safeId(call), safeName(call), "运行已取消，该工具调用未执行");
    }

    private ToolCallId safeId(ToolCallBlock call) {
        try {
            return new ToolCallId(call.id());
        } catch (DomainException | IllegalArgumentException error) {
            return new ToolCallId("invalid-call");
        }
    }

    private ToolName safeName(ToolCallBlock call) {
        try {
            return new ToolName(call.name());
        } catch (DomainException | IllegalArgumentException error) {
            return UNKNOWN_TOOL;
        }
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
     * 预检后的单个调用。
     *
     * @param call 原始调用
     * @param descriptor 工具定义；未知工具为 null
     * @param rejection 预检拒绝结果；可执行时为 null
     * @param budgetBlocked 是否因预算被拒
     */
    private record PreparedCall(ToolCallBlock call, ToolDescriptor descriptor,
            ToolResult rejection, boolean budgetBlocked) {

        private boolean executable() {
            return rejection == null;
        }

        private PreparedCall withRejection(ToolResult newRejection, boolean newBudgetBlocked) {
            return new PreparedCall(call, descriptor, newRejection, newBudgetBlocked);
        }
    }
}
