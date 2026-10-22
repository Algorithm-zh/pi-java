package com.piagent.application.agent.command;

import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.util.Objects;

/**
 * 发送用户消息并启动 run 的命令。
 *
 * @param sessionId 会话标识
 * @param text 用户文本
 */
public record SendMessageCommand(SessionId sessionId, String text) {

    private static final int MAX_TEXT_LENGTH = 100_000;

    /**
     * 校验命令。
     *
     * @param sessionId 会话标识
     * @param text 用户文本
     */
    public SendMessageCommand {
        Objects.requireNonNull(sessionId, "sessionId 不能为空");
        text = Guard.notBlank(text, DomainErrorCode.INVALID_ARGUMENT, "消息内容不能为空");
        Guard.maxLength(text, MAX_TEXT_LENGTH, DomainErrorCode.INVALID_ARGUMENT,
                "消息内容不能超过 " + MAX_TEXT_LENGTH + " 个字符");
    }
}
