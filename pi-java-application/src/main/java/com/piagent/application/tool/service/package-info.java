/**
 * 工具上下文应用服务。
 *
 * <p>用例服务只编排领域端口与策略，不直接执行工具；执行与超时由基础设施层的
 * {@code ToolInvoker} 负责，Agent Loop 在 M4 接入。</p>
 */
package com.piagent.application.tool.service;
