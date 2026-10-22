package com.piagent.infrastructure.plugin;

import com.piagent.domain.extension.model.PluginId;
import com.piagent.domain.tool.model.ExecutionMode;
import com.piagent.domain.tool.model.ToolDescriptor;
import com.piagent.domain.tool.model.ToolInvocation;
import com.piagent.domain.tool.model.ToolResult;
import com.piagent.domain.tool.model.ToolRisk;
import com.piagent.plugin.api.CancellationToken;
import com.piagent.plugin.api.PluginLogger;
import com.piagent.plugin.api.PluginTool;
import com.piagent.plugin.api.PluginToolCall;
import com.piagent.plugin.api.PluginToolContext;
import com.piagent.plugin.api.PluginToolResult;
import com.piagent.plugin.api.ToolSpec;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 插件工具到领域工具模型的适配器。
 *
 * <p>插件契约不允许引用领域类型，因此这里负责风险映射、完整名称拼接与结果转换。
 * 执行期间切换线程上下文类加载器，兼容依赖 TCCL 的第三方库。</p>
 */
final class PluginToolAdapter {

    private final PluginId pluginId;
    private final PluginTool tool;
    private final ToolDescriptor descriptor;
    private final ClassLoader classLoader;
    private final PluginLogger logger;

    PluginToolAdapter(PluginId pluginId, PluginTool tool, ClassLoader classLoader,
            PluginLogger logger) {
        this.pluginId = Objects.requireNonNull(pluginId, "pluginId 不能为空");
        this.tool = Objects.requireNonNull(tool, "tool 不能为空");
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader 不能为空");
        this.logger = Objects.requireNonNull(logger, "logger 不能为空");
        ToolSpec spec = Objects.requireNonNull(tool.spec(), "插件工具必须提供 spec");
        this.descriptor = new ToolDescriptor(pluginId.toolName(spec.name()), spec.description(),
                spec.inputSchemaJson(), riskOf(spec.risk()),
                spec.parallelSafe() ? ExecutionMode.PARALLEL : ExecutionMode.SEQUENTIAL,
                spec.timeout());
    }

    ToolDescriptor descriptor() {
        return descriptor;
    }

    PluginId pluginId() {
        return pluginId;
    }

    String schemaJson() {
        return descriptor.inputSchemaJson();
    }

    ToolResult invoke(ToolInvocation invocation) {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(classLoader);
        try {
            PluginToolContext context = new HostToolContext(invocation.sandbox().workspaceRoot());
            PluginToolResult result = tool.execute(new PluginToolCall(invocation.callId().value(),
                    descriptor.name().value(), invocation.arguments()), context);
            if (result == null) {
                return ToolResult.error(invocation.callId(), descriptor.name(),
                        "插件工具返回了 null", errorDetails("NULL_RESULT"));
            }
            return new ToolResult(invocation.callId(), descriptor.name(), result.content(),
                    result.error(), details(result));
        } catch (Exception error) {
            return ToolResult.error(invocation.callId(), descriptor.name(),
                    "插件工具执行失败: " + message(error), errorDetails("PLUGIN_TOOL_FAILED"));
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private Map<String, Object> details(PluginToolResult result) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("pluginId", pluginId.value());
        if (!result.metadata().isEmpty()) {
            details.put("metadata", result.metadata());
        }
        if (result.error()) {
            details.put("errorCode", "PLUGIN_TOOL_ERROR");
        }
        return details;
    }

    private Map<String, Object> errorDetails(String code) {
        return Map.of("pluginId", pluginId.value(), "errorCode", code,
                "tool", descriptor.name().value());
    }

    private String message(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static ToolRisk riskOf(com.piagent.plugin.api.PluginRisk risk) {
        if (risk == null) {
            throw new IllegalArgumentException("插件工具风险不能为空");
        }
        return switch (risk) {
            case LOW -> ToolRisk.LOW;
            case MEDIUM -> ToolRisk.MEDIUM;
            case HIGH -> ToolRisk.HIGH;
            case CRITICAL -> ToolRisk.CRITICAL;
        };
    }

    /**
     * 宿主提供的工具执行上下文。
     */
    private final class HostToolContext implements PluginToolContext {

        private final Path workspaceRoot;

        private HostToolContext(Path workspaceRoot) {
            this.workspaceRoot = workspaceRoot;
        }

        @Override
        public Path workspaceRoot() {
            return workspaceRoot;
        }

        @Override
        public CancellationToken cancellation() {
            return () -> Thread.currentThread().isInterrupted();
        }

        @Override
        public PluginLogger log() {
            return logger;
        }

        @Override
        public void progress(String partialOutput) {
            // M5 工具事件流尚未接入；保留契约，避免插件因空实现而无法编译。
        }
    }
}
