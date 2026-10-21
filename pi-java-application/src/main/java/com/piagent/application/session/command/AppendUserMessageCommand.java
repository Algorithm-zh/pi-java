package com.piagent.application.session.command;

import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.util.Objects;

/**
 * 追加用户消息命令。
 *
 * @param sessionId 会话标识
 * @param text 用户文本
 */
public record AppendUserMessageCommand(SessionId sessionId, String text) {

    /**
     * 校验命令。
     *
     * @param sessionId 会话标识
     * @param text 文本
     */
    public AppendUserMessageCommand {
        Objects.requireNonNull(sessionId, "sessionId 不能为空");
        text = Guard.notBlank(text, DomainErrorCode.INVALID_ARGUMENT, "用户消息不能为空");
    }
}
