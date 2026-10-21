package com.piagent.application.session.command;

import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.SessionId;
import java.util.Objects;

/**
 * 移动会话当前分支命令。
 *
 * @param sessionId 会话标识
 * @param targetEntryId 目标叶子
 */
public record MoveBranchCommand(SessionId sessionId, EntryId targetEntryId) {

    /**
     * 校验命令。
     *
     * @param sessionId 会话标识
     * @param targetEntryId 目标叶子
     */
    public MoveBranchCommand {
        Objects.requireNonNull(sessionId, "sessionId 不能为空");
        Objects.requireNonNull(targetEntryId, "targetEntryId 不能为空");
    }
}
