package com.piagent.domain.session.model.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import org.junit.jupiter.api.Test;

/**
 * 图片内容块校验测试。
 *
 * <p>图片块只保存附件引用；模型类型必须落在 image/ 下，名称允许缺省但要有上限。</p>
 */
class ImageBlockTest {

    @Test
    void normalizesMediaTypeAndName() {
        ImageBlock block = new ImageBlock(" att-1 ", "IMAGE/PNG", "  shot.png  ");

        assertThat(block.attachmentId()).isEqualTo("att-1");
        assertThat(block.mediaType()).isEqualTo("image/png");
        assertThat(block.name()).isEqualTo("shot.png");
    }

    @Test
    void defaultsBlankNameToImage() {
        assertThat(new ImageBlock("att-1", "image/jpeg", null).name()).isEqualTo("image");
        assertThat(new ImageBlock("att-1", "image/jpeg", "   ").name()).isEqualTo("image");
    }

    @Test
    void rejectsNonImageMediaType() {
        assertThatThrownBy(() -> new ImageBlock("att-1", "text/plain", "notes.txt"))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.INVALID_ARGUMENT));
    }

    @Test
    void rejectsBlankAttachmentIdOrMediaType() {
        assertThatThrownBy(() -> new ImageBlock(" ", "image/png", "a.png"))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.INVALID_ARGUMENT));
        assertThatThrownBy(() -> new ImageBlock("att-1", " ", "a.png"))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.INVALID_ARGUMENT));
    }

    @Test
    void rejectsOverlongName() {
        assertThatThrownBy(() -> new ImageBlock("att-1", "image/png", "x".repeat(121)))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.INVALID_ARGUMENT));
    }
}
