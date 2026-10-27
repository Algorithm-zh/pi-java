package com.piagent.application.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.application.attachment.service.AttachmentApplicationService;
import com.piagent.application.testsupport.InMemoryAttachmentStore;
import com.piagent.application.testsupport.SequentialIdGenerator;
import com.piagent.domain.session.model.message.ImageBlock;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 附件应用服务测试。
 *
 * <p>覆盖上传边界（空内容、超限、格式）、附件 ID 解析与错误码，确保发送链路
 * 拿到的图片块只引用已保存的附件。</p>
 */
class AttachmentApplicationServiceTest {

    private static final byte[] PNG = {
        (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0
    };

    private final InMemoryAttachmentStore store =
            new InMemoryAttachmentStore(new SequentialIdGenerator());
    private final AttachmentApplicationService service = new AttachmentApplicationService(store);

    @Test
    void uploadsImageAndReturnsMetadata() {
        AttachmentMetadata metadata = service.upload("shot.png", PNG);

        assertThat(metadata.name()).isEqualTo("shot.png");
        assertThat(metadata.mediaType()).isEqualTo("image/png");
        assertThat(metadata.size()).isEqualTo(PNG.length);
        assertThat(store.find(metadata.id())).contains(metadata);
    }

    @Test
    void rejectsEmptyAndOversizedContent() {
        assertThatThrownBy(() -> service.upload("empty.png", new byte[0]))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.ATTACHMENT_INVALID));
        byte[] oversized = new byte[(int) AttachmentApplicationService.MAX_IMAGE_BYTES + 1];
        assertThatThrownBy(() -> service.upload("big.png", oversized))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.ATTACHMENT_INVALID));
    }

    @Test
    void rejectsUnsupportedFormat() {
        assertThatThrownBy(() -> service.upload("notes.txt", "plain text content".getBytes()))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.ATTACHMENT_INVALID));
    }

    @Test
    void resolvesAttachmentIdsToImageBlocks() {
        AttachmentMetadata first = service.upload("one.png", PNG);
        AttachmentMetadata second = service.upload("two.png", PNG);

        List<ImageBlock> blocks = service.imageBlocks(List.of(first.id(), second.id()));

        assertThat(blocks).containsExactly(
                new ImageBlock(first.id(), "image/png", "one.png"),
                new ImageBlock(second.id(), "image/png", "two.png"));
        assertThat(service.imageBlocks(null)).isEmpty();
        assertThat(service.imageBlocks(List.of())).isEmpty();
    }

    @Test
    void rejectsTooManyDuplicateOrUnknownAttachments() {
        AttachmentMetadata metadata = service.upload("one.png", PNG);

        assertThatThrownBy(() -> service.imageBlocks(
                List.of("a", "b", "c", "d", "e")))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.INVALID_ARGUMENT));
        assertThatThrownBy(() -> service.imageBlocks(List.of(metadata.id(), metadata.id())))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.INVALID_ARGUMENT));
        assertThatThrownBy(() -> service.imageBlocks(List.of("missing-attachment")))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.ATTACHMENT_NOT_FOUND));
    }

    @Test
    void loadsContentOrReportsNotFound() {
        AttachmentMetadata metadata = service.upload("one.png", PNG);

        AttachmentContent content = service.load(metadata.id());

        assertThat(content.metadata()).isEqualTo(metadata);
        assertThat(content.content()).containsExactly(PNG);
        assertThatThrownBy(() -> service.load("missing-attachment"))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.ATTACHMENT_NOT_FOUND));
    }
}
