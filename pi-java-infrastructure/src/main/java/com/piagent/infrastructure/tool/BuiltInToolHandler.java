package com.piagent.infrastructure.tool;

import com.piagent.domain.tool.model.ToolInvocation;
import com.piagent.domain.tool.model.ToolResult;

/**
 * 内置工具执行体。
 *
 * <p>实现类只处理参数与 IO，不负责超时、异常包装与错误码落库；
 * 这些横切逻辑统一由 {@link BuiltInToolInvoker} 完成。</p>
 */
@FunctionalInterface
public interface BuiltInToolHandler {

    /**
     * 执行工具。
     *
     * @param invocation 调用上下文（含沙箱策略）
     * @return 工具结果
     */
    ToolResult execute(ToolInvocation invocation);
}
