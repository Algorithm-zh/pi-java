package com.piagent.domain.tool.model;

import com.piagent.domain.governance.model.SandboxPolicy;
import java.util.Map;
import java.util.Objects;

/**
 * 一次工具调用。
 *
 * <p>参数来自模型返回的 JSON 对象，保持不可变；沙箱策略在调用开始时冻结，
 * 避免运行中途修改工作区配置导致同一批工具使用不同边界。</p>
 *
 * @param callId 调用 ID
 * @param tool 工具名
 * @param arguments 参数
 * @param sandbox 本次调用的沙箱策略
 */
public record ToolInvocation(ToolCallId callId, ToolName tool, Map<String, Object> arguments,
        SandboxPolicy sandbox) {

    /**
     * 校验调用。
     *
     * @param callId 调用 ID
     * @param tool 工具名
     * @param arguments 参数
     * @param sandbox 沙箱策略
     */
    public ToolInvocation {
        Objects.requireNonNull(callId, "callId 不能为空");
        Objects.requireNonNull(tool, "tool 不能为空");
        Objects.requireNonNull(arguments, "arguments 不能为空");
        Objects.requireNonNull(sandbox, "sandbox 不能为空");
        arguments = Map.copyOf(arguments);
    }
}
