/**
 * 治理上下文的应用服务。
 *
 * <p>审批用例负责工具调用的人工决策；它不执行工具、不推进 run，只维护审批
 * 状态机、审计 entry 与 SSE 事件。工程上下文（策略、沙箱）见领域的
 * {@code com.piagent.domain.governance}。</p>
 */
package com.piagent.application.governance.service;
