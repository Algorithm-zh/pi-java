package com.piagent.application.attachment.port;

import com.piagent.application.attachment.AttachmentContent;
import com.piagent.application.attachment.AttachmentMetadata;
import com.piagent.application.attachment.ImageFormat;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 附件存储端口。
 *
 * <p>只负责按 ID 保存与读取；格式识别、大小限制、内容清洗与配额判断由应用服务
 * 在写入前完成。写入时携带内容摘要，用于相同图片的去重。</p>
 */
public interface AttachmentStore {

    /**
     * 保存附件；摘要相同的附件应复用已有记录。
     *
     * @param fileName 原始文件名
     * @param content 二进制内容（已清洗）
     * @param format 已识别的图片格式
     * @param sha256 内容的 SHA-256 十六进制摘要
     * @return 元数据
     */
    AttachmentMetadata save(String fileName, byte[] content, ImageFormat format, String sha256);

    /**
     * 查询元数据。
     *
     * @param id 附件标识
     * @return 元数据；不存在时为 empty
     */
    Optional<AttachmentMetadata> find(String id);

    /**
     * 读取附件内容。
     *
     * @param id 附件标识
     * @return 元数据与内容；不存在时为 empty
     */
    Optional<AttachmentContent> load(String id);

    /**
     * 按内容摘要查询附件。
     *
     * @param sha256 SHA-256 十六进制摘要
     * @return 元数据；不存在时为 empty
     */
    Optional<AttachmentMetadata> findByDigest(String sha256);

    /**
     * 列出全部已提交附件。
     *
     * @return 附件与写入时间
     */
    List<StoredAttachment> list();

    /**
     * 统计已提交附件的二进制总字节数。
     *
     * @return 总字节数
     */
    long totalBytes();

    /**
     * 删除附件的二进制与元数据。
     *
     * @param id 附件标识
     * @return 是否删除了至少一个文件
     */
    boolean delete(String id);

    /**
     * 已存储附件及其写入时间。
     *
     * @param metadata 元数据
     * @param createdAt 写入完成时间
     */
    record StoredAttachment(AttachmentMetadata metadata, Instant createdAt) {
    }
}
