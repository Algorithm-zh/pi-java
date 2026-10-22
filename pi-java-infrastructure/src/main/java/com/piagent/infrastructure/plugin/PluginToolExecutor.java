package com.piagent.infrastructure.plugin;

import com.piagent.domain.extension.model.PluginId;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.tool.model.ToolInvocation;
import com.piagent.domain.tool.model.ToolName;
import com.piagent.domain.tool.model.ToolResult;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 插件工具执行器：按工具名分派、超时与异常包装。
 *
 * <p>工具句柄在插件停用后仍保留，使启用期间启动的 run 可以用旧快照继续完成；
 * 句柄随宿主关闭统一释放。超时通过中断虚拟线程实现，插件应检查取消令牌。</p>
 */
final class PluginToolExecutor {

    private final ExecutorService executor;
    private final Map<ToolName, PluginToolAdapter> handles = new ConcurrentHashMap<>();

    PluginToolExecutor(ExecutorService executor) {
        this.executor = executor;
    }

    void register(List<PluginToolAdapter> adapters) {
        for (PluginToolAdapter adapter : adapters) {
            handles.put(adapter.descriptor().name(), adapter);
        }
    }

    boolean has(ToolName name) {
        return handles.containsKey(name);
    }

    ToolResult invoke(ToolInvocation invocation) {
        PluginToolAdapter adapter = handles.get(invocation.tool());
        if (adapter == null) {
            throw new DomainException(DomainErrorCode.TOOL_NOT_FOUND,
                    "插件工具未加载: " + invocation.tool());
        }
        Future<ToolResult> future;
        try {
            future = executor.submit(() -> adapter.invoke(invocation));
        } catch (RejectedExecutionException error) {
            return ToolResult.error(invocation.callId(), invocation.tool(), "工具执行器已关闭");
        }
        long timeoutMs = adapter.descriptor().timeout().toMillis();
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            future.cancel(true);
            return ToolResult.error(invocation.callId(), invocation.tool(),
                    "插件工具执行超时: " + invocation.tool(),
                    Map.of("errorCode", DomainErrorCode.TOOL_TIMEOUT.name(), "timeoutMs", timeoutMs));
        } catch (InterruptedException error) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return ToolResult.error(invocation.callId(), invocation.tool(), "工具执行被取消",
                    Map.of("errorCode", "CANCELLED"));
        } catch (ExecutionException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            return ToolResult.error(invocation.callId(), invocation.tool(),
                    "插件工具执行异常: " + cause.getMessage(),
                    Map.of("errorCode", "PLUGIN_TOOL_FAILED",
                            "failureType", cause.getClass().getSimpleName()));
        }
    }

    void removeAll(PluginId pluginId) {
        handles.entrySet().removeIf(entry -> entry.getValue().pluginId().equals(pluginId));
    }

    void clear() {
        handles.clear();
    }
}
