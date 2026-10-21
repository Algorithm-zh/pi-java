package com.piagent.infrastructure.tool;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.tool.model.ToolDescriptor;
import com.piagent.domain.tool.model.ToolInvocation;
import com.piagent.domain.tool.model.ToolName;
import com.piagent.domain.tool.model.ToolResult;
import com.piagent.domain.tool.port.ToolInvoker;
import com.piagent.domain.tool.port.ToolRegistry;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 内置工具调用器：超时、异常包装与虚拟线程调度。
 *
 * <p>所有工具都在虚拟线程上执行，阻塞 IO 不占平台线程；等待时长取
 * {@link ToolDescriptor#timeout()}，超时后取消任务并返回错误结果。工具失败
 * 不向 Agent Loop 抛异常——模型需要看到失败原因并自行纠正，只有
 * “工具未注册”这种编程错误才会向调用方抛出领域异常。</p>
 */
public final class BuiltInToolInvoker implements ToolInvoker, AutoCloseable {

    private final ToolRegistry registry;
    private final Map<ToolName, BuiltInToolHandler> handlers;
    private final ExecutorService executor;

    /**
     * 创建调用器。
     *
     * @param registry 工具目录
     * @param handlers 按工具名索引的执行体
     */
    public BuiltInToolInvoker(ToolRegistry registry, Map<ToolName, BuiltInToolHandler> handlers) {
        this.registry = Objects.requireNonNull(registry, "registry 不能为空");
        this.handlers = Map.copyOf(handlers);
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public ToolResult invoke(ToolInvocation invocation) {
        Objects.requireNonNull(invocation, "invocation 不能为空");
        ToolDescriptor descriptor = registry.require(invocation.tool());
        BuiltInToolHandler handler = handlers.get(invocation.tool());
        if (handler == null) {
            throw new DomainException(DomainErrorCode.TOOL_NOT_FOUND,
                    "工具没有可用实现: " + invocation.tool());
        }
        Future<ToolResult> future;
        try {
            future = executor.submit(() -> handler.execute(invocation));
        } catch (RejectedExecutionException error) {
            return failure(invocation, "工具执行器已关闭", "TOOL_EXECUTION_FAILED", Map.of());
        }
        long timeoutMs = descriptor.timeout().toMillis();
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            future.cancel(true);
            return failure(invocation, "工具执行超时: " + invocation.tool(),
                    DomainErrorCode.TOOL_TIMEOUT.name(), Map.of("timeoutMs", timeoutMs));
        } catch (InterruptedException error) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return failure(invocation, "工具执行被取消", "CANCELLED", Map.of());
        } catch (ExecutionException error) {
            return mapFailure(invocation, error.getCause());
        }
    }

    private ToolResult mapFailure(ToolInvocation invocation, Throwable cause) {
        if (cause instanceof DomainException domain) {
            return failure(invocation, domain.getMessage(), domain.code().name(), Map.of());
        }
        String message = cause == null || cause.getMessage() == null
                ? "工具执行失败"
                : "工具执行失败: " + cause.getMessage();
        Map<String, Object> details = new HashMap<>();
        if (cause != null) {
            details.put("failureType", cause.getClass().getSimpleName());
        }
        return failure(invocation, message, DomainErrorCode.TOOL_EXECUTION_FAILED.name(), details);
    }

    private ToolResult failure(ToolInvocation invocation, String message, String errorCode,
            Map<String, Object> extra) {
        Map<String, Object> details = new HashMap<>(extra);
        details.put("errorCode", errorCode);
        details.put("tool", invocation.tool().value());
        return ToolResult.error(invocation.callId(), invocation.tool(), message, details);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
