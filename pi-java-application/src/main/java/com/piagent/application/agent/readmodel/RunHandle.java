package com.piagent.application.agent.readmodel;

import com.piagent.domain.agent.RunId;
import com.piagent.domain.session.model.SessionId;
import java.util.Objects;

/**
 * 启动 run 后返回给触发层的句柄。
 *
 * @param runId 运行标识
 * @param sessionId 会话标识
 */
public record RunHandle(RunId runId, SessionId sessionId) {

    /**
     * 校验句柄。
     *
     * @param runId 运行标识
     * @param sessionId 会话标识
     */
    public RunHandle {
        Objects.requireNonNull(runId, "runId 不能为空");
        Objects.requireNonNull(sessionId, "sessionId 不能为空");
    }
}
