package com.piagent.infrastructure.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.piagent.domain.tool.model.ExecutionMode;
import com.piagent.domain.tool.model.ToolDescriptor;
import com.piagent.domain.tool.model.ToolInvocation;
import com.piagent.domain.tool.model.ToolName;
import com.piagent.domain.tool.model.ToolResult;
import com.piagent.infrastructure.config.McpSettings;
import java.util.Map;
import java.util.Objects;

/**
 * 远程 MCP 工具到领域工具模型的适配器。
 *
 * <p>工具名在注册时一次性确定，之后不随远程目录变化；调用失败（连接断开、
 * 超时、远程报错）都返回 {@code error=true} 的结果，不打断 Agent Loop。</p>
 */
final class McpToolAdapter {

    private final ToolDescriptor descriptor;
    private final String remoteName;
    private final McpServerRuntime runtime;

    McpToolAdapter(McpSettings.Server server, McpToolInfo tool, McpRiskMapper risks,
            McpServerRuntime runtime, ObjectMapper mapper) {
        Objects.requireNonNull(server, "server 不能为空");
        Objects.requireNonNull(tool, "tool 不能为空");
        this.remoteName = tool.remoteName();
        this.runtime = Objects.requireNonNull(runtime, "runtime 不能为空");
        String fullName = McpToolNamespace.qualify(server.name(), remoteName);
        this.descriptor = new ToolDescriptor(new ToolName(fullName), tool.description(),
                schemaJson(tool, mapper), risks.riskOf(remoteName),
                ExecutionMode.SEQUENTIAL, server.timeouts().request());
    }

    ToolDescriptor descriptor() {
        return descriptor;
    }

    ToolResult invoke(ToolInvocation invocation) {
        try {
            McpClient client = runtime.clientForCall();
            McpCallResult result = client.call(remoteName, invocation.arguments(),
                    descriptor.timeout());
            return new ToolResult(invocation.callId(), descriptor.name(), result.content(),
                    result.error(), result.details());
        } catch (McpException error) {
            return failure(invocation, error.getMessage(), error.errorCode());
        } catch (RuntimeException error) {
            String message = error.getMessage() == null
                    ? "MCP 工具执行失败" : "MCP 工具执行失败: " + error.getMessage();
            return failure(invocation, message, "MCP_TOOL_FAILED");
        }
    }

    private ToolResult failure(ToolInvocation invocation, String message, String errorCode) {
        return ToolResult.error(invocation.callId(), descriptor.name(), message,
                Map.of("errorCode", errorCode, "server", runtime.serverName(),
                        "mcpTool", remoteName));
    }

    private static String schemaJson(McpToolInfo tool, ObjectMapper mapper) {
        try {
            return mapper.writeValueAsString(tool.schema());
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("无法序列化 MCP 工具 schema: " + tool.remoteName(),
                    error);
        }
    }
}
