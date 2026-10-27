package com.piagent.application.agent.command;

import com.piagent.application.attachment.service.AttachmentApplicationService;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.util.List;
import java.util.Objects;

/**
 * 发送用户消息并启动 run 的命令。
 *
 * <p>文本与图片附件至少提供一个；图片只传附件 ID，二进制已经在附件存储中。</p>
 *
 * @param sessionId 会话标识
 * @param text 用户文本，可为空串
 * @param attachmentIds 图片附件 ID，可为空列表
 */
public record SendMessageCommand(SessionId sessionId, String text, List<String> attachmentIds) {

    private static final int MAX_TEXT_LENGTH = 100_000;

    /**
     * 校验命令。
     *
     * @param sessionId 会话标识
     * @param text 用户文本
     * @param attachmentIds 图片附件 ID
     */
    public SendMessageCommand {
        Objects.requireNonNull(sessionId, "sessionId 不能为空");
        text = text == null ? "" : text;
        Guard.maxLength(text, MAX_TEXT_LENGTH, DomainErrorCode.INVALID_ARGUMENT,
                "消息内容不能超过 " + MAX_TEXT_LENGTH + " 个字符");
        attachmentIds = attachmentIds == null ? List.of() : List.copyOf(attachmentIds);
        Guard.require(attachmentIds.size() <= AttachmentApplicationService.MAX_IMAGES_PER_MESSAGE,
                DomainErrorCode.INVALID_ARGUMENT,
                "一条消息最多 " + AttachmentApplicationService.MAX_IMAGES_PER_MESSAGE + " 张图片");
        Guard.require(!text.isBlank() || !attachmentIds.isEmpty(),
                DomainErrorCode.INVALID_ARGUMENT, "消息内容不能为空");
    }

    /**
     * 纯文本消息的便捷构造。
     *
     * @param sessionId 会话标识
     * @param text 用户文本
     */
    public SendMessageCommand(SessionId sessionId, String text) {
        this(sessionId, text, List.of());
    }
}
