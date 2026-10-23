package com.piagent.application.session.command;

import com.piagent.domain.session.model.SessionId;

/**
 * 重命名会话命令。
 *
 * @param sessionId 会话标识
 * @param title 新标题
 */
public record RenameSessionCommand(SessionId sessionId, String title) {
}
