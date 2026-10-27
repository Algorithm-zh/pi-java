package com.piagent.infrastructure.attachment;

import com.piagent.application.attachment.ImageFormat;
import com.piagent.application.attachment.port.ImageSanitizer;
import java.util.Objects;

/**
 * 按图片格式移除隐私元数据。
 *
 * <p>只删除 EXIF/XMP/注释等附属块，不改动像素数据；结构不符合预期时原样返回，
 * 避免因为清理器 bug 损坏用户图片。GIF 只移除注释块，保留循环播放等应用扩展。</p>
 */
public final class ImageMetadataStripper implements ImageSanitizer {

    @Override
    public byte[] strip(byte[] content, ImageFormat format) {
        Objects.requireNonNull(content, "content 不能为空");
        Objects.requireNonNull(format, "format 不能为空");
        return switch (format.mediaType()) {
            case "image/jpeg" -> JpegStrip.strip(content);
            case "image/png" -> PngStrip.strip(content);
            case "image/webp" -> WebpStrip.strip(content);
            case "image/gif" -> GifStrip.strip(content);
            default -> content;
        };
    }
}
