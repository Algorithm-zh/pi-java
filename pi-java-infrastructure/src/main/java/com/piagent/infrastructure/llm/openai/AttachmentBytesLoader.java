package com.piagent.infrastructure.llm.openai;

/**
 * 读取图片附件原始字节。
 *
 * <p>请求映射只关心“如何编码”，文件位置由装配层决定；附件缺失时返回 null，
 * 映射器会降级为一条文本占位，而不是让整次请求失败。</p>
 */
@FunctionalInterface
public interface AttachmentBytesLoader {

    /**
     * 读取附件内容。
     *
     * @param attachmentId 附件标识
     * @return 原始字节；不存在或不可读时返回 null
     */
    byte[] load(String attachmentId);
}
