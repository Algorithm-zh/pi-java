package com.piagent.application.attachment.port;

import com.piagent.application.attachment.ImageFormat;

/**
 * 图片内容清洗端口。
 *
 * <p>在落盘与发送给模型之前移除 EXIF、XMP、文本块等隐私元数据；实现必须
 * 保持像素数据与可解码性不变，无法安全解析时原样返回。</p>
 */
public interface ImageSanitizer {

    /**
     * 清洗图片元数据。
     *
     * @param content 原始字节
     * @param format 已识别的格式
     * @return 清洗后的字节；没有可移除内容时返回原数组
     */
    byte[] strip(byte[] content, ImageFormat format);
}
