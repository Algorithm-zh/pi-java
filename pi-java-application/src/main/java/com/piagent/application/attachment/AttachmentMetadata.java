package com.piagent.application.attachment;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.util.Objects;

/**
 * 已保存附件的元数据。
 *
 * @param id 附件标识
 * @param name 原始文件名（仅用于展示）
 * @param mediaType 媒体类型
 * @param size 字节数
 */
public record AttachmentMetadata(String id, String name, String mediaType, long size) {

    /**
     * 校验元数据。
     *
     * @param id 附件标识
     * @param name 文件名
     * @param mediaType 媒体类型
     * @param size 字节数
     */
    public AttachmentMetadata {
        id = Guard.notBlank(id, DomainErrorCode.INVALID_ARGUMENT, "附件 ID 不能为空");
        name = Guard.notBlank(name, DomainErrorCode.INVALID_ARGUMENT, "附件名不能为空");
        mediaType = Guard.notBlank(mediaType, DomainErrorCode.INVALID_ARGUMENT, "媒体类型不能为空");
        Objects.requireNonNull(mediaType, "mediaType 不能为空");
        Guard.require(size > 0, DomainErrorCode.INVALID_ARGUMENT, "附件大小必须大于 0");
    }
}
