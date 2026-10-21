package com.piagent.application.session.command;

import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.SessionId;
import java.util.Objects;

/**
 * 从已有会话 fork 新会话命令。
 *
 * @param sourceSessionId 源会话
 * @param fromEntryId 分支起点
 * @param title 新会话标题；为 null 时自动生成
 */
public record ForkSessionCommand(SessionId sourceSessionId, EntryId fromEntryId, String title) {

    /**
     * 校验命令。
     *
     * @param sourceSessionId 源会话
     * @param fromEntryId 起点
     * @param title 标题
     */
    public ForkSessionCommand {
        Objects.requireNonNull(sourceSessionId, "sourceSessionId 不能为空");
        Objects.requireNonNull(fromEntryId, "fromEntryId 不能为空");
    }
}
