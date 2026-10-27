package com.piagent.application.attachment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 图片格式识别测试。
 *
 * <p>只看文件头，不信任扩展名；SVG 等可包含脚本的格式必须拒绝。</p>
 */
class ImageFormatsTest {

    @Test
    void detectsSupportedFormatsByMagicBytes() {
        assertThat(ImageFormats.detect(bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0)))
                .hasValueSatisfying(format -> {
                    assertThat(format.mediaType()).isEqualTo("image/png");
                    assertThat(format.extension()).isEqualTo("png");
                });
        assertThat(ImageFormats.detect(bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 0, 0, 0, 0, 0, 0, 0)))
                .hasValueSatisfying(format -> assertThat(format.mediaType()).isEqualTo("image/jpeg"));
        assertThat(ImageFormats.detect(bytes('G', 'I', 'F', '8', '9', 'a', 0, 0, 0, 0, 0, 0)))
                .hasValueSatisfying(format -> assertThat(format.mediaType()).isEqualTo("image/gif"));
        assertThat(ImageFormats.detect(bytes('R', 'I', 'F', 'F', 0, 0, 0, 0,
                'W', 'E', 'B', 'P')))
                .hasValueSatisfying(format -> assertThat(format.mediaType()).isEqualTo("image/webp"));
    }

    @Test
    void rejectsSvgAndUnknownContent() {
        assertThat(ImageFormats.detect(bytes("<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>")))
                .isEmpty();
        assertThat(ImageFormats.detect(bytes("GIF88a-bytes"))).isEmpty();
        assertThat(ImageFormats.detect(bytes("not an image at all"))).isEmpty();
    }

    @Test
    void rejectsNullAndShortContent() {
        assertThat(ImageFormats.detect(null)).isEmpty();
        assertThat(ImageFormats.detect(bytes(0x89, 'P', 'N', 'G'))).isEmpty();
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int index = 0; index < values.length; index++) {
            result[index] = (byte) values[index];
        }
        return result;
    }

    private static byte[] bytes(String text) {
        return text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
