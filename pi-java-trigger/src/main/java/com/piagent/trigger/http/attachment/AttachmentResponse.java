package com.piagent.trigger.http.attachment;

import com.piagent.application.attachment.AttachmentMetadata;

/**
 * 附件上传响应。
 *
 * @param id 附件标识
 * @param name 文件名
 * @param mediaType 媒体类型
 * @param size 字节数
 * @param url 读取地址
 */
public record AttachmentResponse(String id, String name, String mediaType, long size, String url) {

    /**
     * 从元数据构造响应。
     *
     * @param metadata 元数据
     * @return 响应
     */
    public static AttachmentResponse from(AttachmentMetadata metadata) {
        return new AttachmentResponse(metadata.id(), metadata.name(), metadata.mediaType(),
                metadata.size(), "/api/v1/attachments/" + metadata.id());
    }
}
