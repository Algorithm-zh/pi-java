package com.piagent.application.attachment;

/**
 * 支持的图片格式。
 *
 * @param mediaType 媒体类型
 * @param extension 文件扩展名，不含点
 */
public record ImageFormat(String mediaType, String extension) {
}
