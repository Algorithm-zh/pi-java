package com.piagent.application.agent.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.application.attachment.service.AttachmentApplicationService;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 发送消息命令校验测试。
 *
 * <p>文本与图片至少提供一个；图片数量上限与应用服务一致，避免命令层与
 * 附件层出现两套口径。</p>
 */
class SendMessageCommandTest {

    private static final SessionId SESSION = SessionId.parse("01TST000000000000000000001");

    @Test
    void normalizesNullTextAndCopiesAttachments() {
        List<String> attachments = new ArrayList<>(List.of("att-1"));
        SendMessageCommand command = new SendMessageCommand(SESSION, null, attachments);
        attachments.add("att-2");

        assertThat(command.text()).isEmpty();
        assertThat(command.attachmentIds()).containsExactly("att-1");
        assertThat(new SendMessageCommand(SESSION, "你好").attachmentIds()).isEmpty();
    }

    @Test
    void acceptsImageOnlyMessage() {
        SendMessageCommand command = new SendMessageCommand(SESSION, "", List.of("att-1"));

        assertThat(command.attachmentIds()).containsExactly("att-1");
    }

    @Test
    void rejectsEmptyMessage() {
        assertThatThrownBy(() -> new SendMessageCommand(SESSION, "   ", List.of()))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.INVALID_ARGUMENT));
    }

    @Test
    void rejectsMoreThanFourAttachments() {
        List<String> attachments = List.of("a", "b", "c", "d", "e");

        assertThatThrownBy(() -> new SendMessageCommand(SESSION, "看图", attachments))
                .isInstanceOfSatisfying(DomainException.class, error -> {
                    assertThat(error.code()).isEqualTo(DomainErrorCode.INVALID_ARGUMENT);
                    assertThat(error.getMessage()).contains(String.valueOf(
                            AttachmentApplicationService.MAX_IMAGES_PER_MESSAGE));
                });
    }

    @Test
    void rejectsOverlongText() {
        assertThatThrownBy(() -> new SendMessageCommand(SESSION, "x".repeat(100_001), List.of()))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.INVALID_ARGUMENT));
    }
}
