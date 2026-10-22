package com.piagent.trigger.http.run;

import com.piagent.application.agent.readmodel.RunHandle;

/**
 * 已启动运行的句柄响应。
 *
 * @param runId 运行 ID
 * @param sessionId 会话 ID
 */
public record RunHandleResponse(String runId, String sessionId) {

    /**
     * 从应用层句柄转换。
     *
     * @param handle 运行句柄
     * @return 响应
     */
    public static RunHandleResponse from(RunHandle handle) {
        return new RunHandleResponse(handle.runId().toString(), handle.sessionId().toString());
    }
}
