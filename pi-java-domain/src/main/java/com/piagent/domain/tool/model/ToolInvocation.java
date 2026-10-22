package com.piagent.domain.tool.model;

import com.piagent.domain.governance.model.SandboxPolicy;
import com.piagent.domain.session.model.SessionId;
import java.util.Map;
import java.util.Objects;

/**
 * 一次工具调用。
 *
 * <p>参数来自模型返回的 JSON 对象，保持不可变；沙箱策略在调用开始时冻结，
 * 避免运行中途修改工作区配置导致同一批工具使用不同边界。</p>
 *
 * <p>{@code originSessionId} 是<b>发起这次调用的会话</b>。大多数工具不需要它，
 * 但 {@code subagent} 必须知道自己的父会话是谁，才能创建子会话、读取父级深度
 * 并把结果挂回会话树。因此它是可空的：手工构造调用（测试、插件直调）可以不传。</p>
 *
 * @param callId 调用 ID
 * @param tool 工具名
 * @param arguments 参数
 * @param sandbox 本次调用的沙箱策略
 * @param originSessionId 发起调用的会话；可为 null
 */
public record ToolInvocation(
        ToolCallId callId,
        ToolName tool,
        Map<String, Object> arguments,
        SandboxPolicy sandbox,
        SessionId originSessionId) {

    /**
     * 校验调用。
     *
     * @param callId 调用 ID
     * @param tool 工具名
     * @param arguments 参数
     * @param sandbox 沙箱策略
     * @param originSessionId 发起调用的会话
     */
    public ToolInvocation {
        Objects.requireNonNull(callId, "callId 不能为空");
        Objects.requireNonNull(tool, "tool 不能为空");
        Objects.requireNonNull(arguments, "arguments 不能为空");
        Objects.requireNonNull(sandbox, "sandbox 不能为空");
        arguments = Map.copyOf(arguments);
    }

    /**
     * 兼容旧调用：不携带发起会话。
     *
     * @param callId 调用 ID
     * @param tool 工具名
     * @param arguments 参数
     * @param sandbox 沙箱策略
     * @return 调用
     */
    public ToolInvocation(ToolCallId callId, ToolName tool, Map<String, Object> arguments,
            SandboxPolicy sandbox) {
        this(callId, tool, arguments, sandbox, null);
    }

    /**
     * 附加发起会话。
     *
     * @param sessionId 会话标识
     * @return 新调用
     */
    public ToolInvocation withOriginSession(SessionId sessionId) {
        return new ToolInvocation(callId, tool, arguments, sandbox, sessionId);
    }
}
