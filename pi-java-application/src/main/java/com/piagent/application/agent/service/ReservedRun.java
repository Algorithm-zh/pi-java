package com.piagent.application.agent.service;

import com.piagent.domain.agent.RunId;

/**
 * 已占用会话槽位的运行句柄。
 *
 * <p>{@code reserve} 成功后，同一会话不会再接受第二个 run；调用方必须
 * 在启动失败时调用 {@code abandon}，或让 run 正常结束。</p>
 *
 * @param runId 运行标识
 * @param events 绑定该 run 的事件发布器
 */
record ReservedRun(RunId runId, RunEventPublisher events) {
}
