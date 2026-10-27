package com.piagent.application.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.application.attachment.service.AttachmentApplicationService;
import com.piagent.application.testsupport.InMemoryAttachmentStore;
import com.piagent.application.testsupport.SequentialIdGenerator;
import com.piagent.domain.session.model.message.ImageBlock;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.util.Arrays;
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
    private static final byte[] PNG_TWO = {
        (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 1
    };

    private final InMemoryAttachmentStore store =
            new InMemoryAttachmentStore(new SequentialIdGenerator());
    private final AttachmentApplicationService service =
            new AttachmentApplicationService(store, (content, format) -> content, 0);

    @Test
    void uploadsImageAndReturnsMetadata() {
        AttachmentMetadata metadata = service.upload("shot.png", PNG);

        assertThat(metadata.name()).isEqualTo("shot.png");
        assertThat(metadata.mediaType()).isEqualTo("image/png");
        assertThat(metadata.size()).isEqualTo(PNG.length);
        assertThat(store.find(metadata.id())).contains(metadata);
        assertThat(service.stats().count()).isEqualTo(1);
        assertThat(service.stats().bytes()).isEqualTo(PNG.length);
        assertThat(service.stats().quotaBytes()).isZero();
    }

    @Test
    void deduplicatesIdenticalContent() {
        AttachmentMetadata first = service.upload("one.png", PNG);
        AttachmentMetadata second = service.upload("copy.png", PNG);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(store.list()).hasSize(1);
        assertThat(store.totalBytes()).isEqualTo(PNG.length);
    }

    @Test
    void storesSanitizedBytesInsteadOfOriginal() {
        byte[] cleaned = Arrays.copyOf(PNG, PNG.length - 1);
        AttachmentApplicationService sanitizing =
                new AttachmentApplicationService(store, (content, format) -> cleaned, 0);

        AttachmentMetadata metadata = sanitizing.upload("one.png", PNG);

        assertThat(metadata.size()).isEqualTo(cleaned.length);
        assertThat(sanitizing.load(metadata.id()).content()).containsExactly(cleaned);
    }

    @Test
    void rejectsUploadsBeyondTotalQuota() {
        AttachmentApplicationService limited = new AttachmentApplicationService(store,
                (content, format) -> content, PNG.length - 1);

        assertThatThrownBy(() -> limited.upload("one.png", PNG))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.ATTACHMENT_QUOTA_EXCEEDED));
        assertThat(store.list()).isEmpty();
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
        AttachmentMetadata second = service.upload("two.png", PNG_TWO);

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
