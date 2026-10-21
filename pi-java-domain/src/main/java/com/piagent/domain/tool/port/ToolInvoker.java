package com.piagent.domain.tool.port;

import com.piagent.domain.tool.model.ToolInvocation;
import com.piagent.domain.tool.model.ToolResult;

/**
 * 工具执行端口。
 *
 * <p>实现必须把参数错误、超时与执行失败转成 {@code error=true} 的结果，
 * 不向 Agent Loop 抛异常；只有持久化等系统性失败才允许向上抛领域异常。</p>
 */
public interface ToolInvoker {

    /**
     * 执行一次工具调用。
     *
     * @param invocation 调用
     * @return 结果
     */
    ToolResult invoke(ToolInvocation invocation);
}
