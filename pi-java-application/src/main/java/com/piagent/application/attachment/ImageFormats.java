package com.piagent.application.attachment;

import java.util.Optional;

/**
 * 按文件头识别图片格式。
 *
 * <p>不信任客户端声明的扩展名与 Content-Type；只接受 PNG、JPEG、WebP 与 GIF，
 * 明确拒绝 SVG 等可执行脚本的容器格式。</p>
 */
public final class ImageFormats {

    private static final ImageFormat PNG = new ImageFormat("image/png", "png");
    private static final ImageFormat JPEG = new ImageFormat("image/jpeg", "jpg");
    private static final ImageFormat WEBP = new ImageFormat("image/webp", "webp");
    private static final ImageFormat GIF = new ImageFormat("image/gif", "gif");

    private ImageFormats() {
    }

    /**
     * 识别图片格式。
     *
     * @param content 文件头字节
     * @return 格式；无法识别时为 empty
     */
    public static Optional<ImageFormat> detect(byte[] content) {
        if (content == null || content.length < 12) {
            return Optional.empty();
        }
        if (startsWith(content, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return Optional.of(PNG);
        }
        if (startsWith(content, 0xFF, 0xD8, 0xFF)) {
            return Optional.of(JPEG);
        }
        if (startsWith(content, 'G', 'I', 'F', '8') && (content[4] == '7' || content[4] == '9')
                && content[5] == 'a') {
            return Optional.of(GIF);
        }
        if (startsWith(content, 'R', 'I', 'F', 'F') && startsWithAt(content, 8, 'W', 'E', 'B', 'P')) {
            return Optional.of(WEBP);
        }
        return Optional.empty();
    }

    private static boolean startsWith(byte[] content, int... expected) {
        return startsWithAt(content, 0, expected);
    }

    private static boolean startsWithAt(byte[] content, int offset, int... expected) {
        if (content.length < offset + expected.length) {
            return false;
        }
        for (int index = 0; index < expected.length; index++) {
            if ((content[offset + index] & 0xFF) != expected[index]) {
                return false;
            }
        }
        return true;
    }
}
