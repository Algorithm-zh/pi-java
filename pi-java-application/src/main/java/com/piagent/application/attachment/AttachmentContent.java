package com.piagent.application.attachment;

import java.util.Objects;

/**
 * 附件元数据与二进制内容。
 *
 * <p>二进制在构造与读取时各复制一次，避免调用方意外修改存储层持有的数组。</p>
 *
 * @param metadata 元数据
 * @param content 二进制内容
 */
public record AttachmentContent(AttachmentMetadata metadata, byte[] content) {

    /**
     * 校验并复制内容。
     *
     * @param metadata 元数据
     * @param content 二进制
     */
    public AttachmentContent {
        Objects.requireNonNull(metadata, "metadata 不能为空");
        Objects.requireNonNull(content, "content 不能为空");
        content = content.clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }
}
