package com.piagent.infrastructure.attachment;

import static org.assertj.core.api.Assertions.assertThat;

import com.piagent.application.attachment.ImageFormat;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * 图片元数据清理测试。
 *
 * <p>用最小的结构样例覆盖四种格式：只移除隐私块，像素/压缩数据与其余块保持
 * 原样；结构损坏时原样返回。</p>
 */
class ImageMetadataStripperTest {

    private static final ImageFormat JPEG = new ImageFormat("image/jpeg", "jpg");
    private static final ImageFormat PNG = new ImageFormat("image/png", "png");
    private static final ImageFormat WEBP = new ImageFormat("image/webp", "webp");
    private static final ImageFormat GIF = new ImageFormat("image/gif", "gif");

    private final ImageMetadataStripper stripper = new ImageMetadataStripper();

    @Test
    void stripsJpegExifAndCommentsButKeepsPixelData() {
        byte[] original = jpeg();

        byte[] cleaned = stripper.strip(original, JPEG);

        String text = new String(cleaned, StandardCharsets.ISO_8859_1);
        assertThat(text).doesNotContain("Exif").doesNotContain("secret").contains("JFIF");
        assertThat(cleaned).containsSequence(segment(0xE0, ascii("JFIF")));
        assertThat(cleaned).containsSequence(segment(0xDA, new byte[] {1, 2, 3}));
        assertThat(cleaned).endsWith((byte) 0xFF, (byte) 0xD9);
    }

    @Test
    void stripsPngTextAndExifChunks() {
        byte[] original = png();

        byte[] cleaned = stripper.strip(original, PNG);

        String text = new String(cleaned, StandardCharsets.ISO_8859_1);
        assertThat(text).doesNotContain("tEXt").doesNotContain("eXIf")
                .doesNotContain("Author").contains("IDAT").contains("IEND");
        assertThat(cleaned).startsWith((byte) 0x89, 'P', 'N', 'G');
    }

    @Test
    void stripsWebpExifAndXmpChunksAndFixesHeader() {
        byte[] original = webp();

        byte[] cleaned = stripper.strip(original, WEBP);

        String text = new String(cleaned, StandardCharsets.ISO_8859_1);
        assertThat(text).doesNotContain("EXIF").doesNotContain("XMP ").contains("VP8X")
                .contains("VP8 ");
        assertThat(cleaned[20]).isZero();
        assertThat(le32(cleaned, 4)).isEqualTo(cleaned.length - 8);
    }

    @Test
    void stripsGifCommentsButKeepsLoopExtension() {
        byte[] original = gif();

        byte[] cleaned = stripper.strip(original, GIF);

        String text = new String(cleaned, StandardCharsets.ISO_8859_1);
        assertThat(text).doesNotContain("hello").contains("NETSCAPE2.0");
        assertThat(cleaned).endsWith((byte) 0x3B);
    }

    @Test
    void returnsOriginalWhenFormatIsUnknown() {
        byte[] content = ascii("not really an image");

        assertThat(stripper.strip(content, new ImageFormat("image/avif", "avif")))
                .isSameAs(content);
    }

    @Test
    void returnsOriginalWhenStructureIsBroken() {
        byte[] truncatedJpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE1, 0, 9};
        byte[] truncatedPng = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0};

        assertThat(stripper.strip(truncatedJpeg, JPEG)).isSameAs(truncatedJpeg);
        assertThat(stripper.strip(truncatedPng, PNG)).isSameAs(truncatedPng);
        byte[] truncatedWebp = ascii("RIFF").clone();
        assertThat(stripper.strip(truncatedWebp, WEBP)).isSameAs(truncatedWebp);
    }

    private static byte[] jpeg() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xD8});
        out.writeBytes(segment(0xE1, ascii("Exif")));
        out.writeBytes(segment(0xFE, ascii("secret")));
        out.writeBytes(segment(0xE0, ascii("JFIF")));
        out.writeBytes(segment(0xDA, new byte[] {1, 2, 3}));
        out.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xD9});
        return out.toByteArray();
    }

    private static byte[] png() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        out.writeBytes(chunk("IHDR", new byte[13]));
        out.writeBytes(chunk("tEXt", ascii("Author")));
        out.writeBytes(chunk("eXIf", new byte[] {1, 2}));
        out.writeBytes(chunk("IDAT", new byte[] {9, 9}));
        out.writeBytes(chunk("IEND", new byte[0]));
        return out.toByteArray();
    }

    private static byte[] webp() {
        byte[] vp8x = new byte[10];
        vp8x[0] = 0x0C;
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(riffChunk("VP8X", vp8x));
        body.writeBytes(riffChunk("EXIF", ascii("gps")));
        body.writeBytes(riffChunk("XMP ", ascii("xmp")));
        body.writeBytes(riffChunk("VP8 ", new byte[] {1, 2, 3, 4}));
        byte[] chunks = body.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(ascii("RIFF"));
        out.writeBytes(new byte[] {0, 0, 0, 0});
        out.writeBytes(ascii("WEBP"));
        out.writeBytes(chunks);
        byte[] result = out.toByteArray();
        writeLe32(result, 4, result.length - 8);
        return result;
    }

    private static byte[] gif() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(ascii("GIF89a"));
        out.writeBytes(new byte[] {1, 0, 1, 0, 0, 0, 0});
        out.writeBytes(new byte[] {0x21, (byte) 0xFE, 5});
        out.writeBytes(ascii("hello"));
        out.write(0);
        out.writeBytes(new byte[] {0x21, (byte) 0xFF, 0x0B});
        out.writeBytes(ascii("NETSCAPE2.0"));
        out.writeBytes(new byte[] {3, 1, 0, 0, 0});
        out.writeBytes(new byte[] {0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 1, 2, 0});
        out.write(0x3B);
        return out.toByteArray();
    }

    private static byte[] segment(int marker, byte[] payload) {
        int length = payload.length + 2;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xFF);
        out.write(marker);
        out.write(length >>> 8);
        out.write(length);
        out.writeBytes(payload);
        return out.toByteArray();
    }

    private static byte[] chunk(String type, byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {
            (byte) (payload.length >>> 24), (byte) (payload.length >>> 16),
            (byte) (payload.length >>> 8), (byte) payload.length
        });
        out.writeBytes(ascii(type));
        out.writeBytes(payload);
        out.writeBytes(new byte[4]);
        return out.toByteArray();
    }

    private static byte[] riffChunk(String fourCc, byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(ascii(fourCc));
        out.write(payload.length);
        out.write(payload.length >>> 8);
        out.write(payload.length >>> 16);
        out.write(payload.length >>> 24);
        out.writeBytes(payload);
        if ((payload.length & 1) == 1) {
            out.write(0);
        }
        return out.toByteArray();
    }

    private static int le32(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16) | ((data[offset + 3] & 0xFF) << 24);
    }

    private static void writeLe32(byte[] data, int offset, int value) {
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >>> 8);
        data[offset + 2] = (byte) (value >>> 16);
        data[offset + 3] = (byte) (value >>> 24);
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }
}
