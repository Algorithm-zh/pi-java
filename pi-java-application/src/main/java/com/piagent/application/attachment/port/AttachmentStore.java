package com.piagent.application.attachment.port;

import com.piagent.application.attachment.AttachmentContent;
import com.piagent.application.attachment.AttachmentMetadata;
import com.piagent.application.attachment.ImageFormat;
import java.util.Optional;

/**
 * 附件存储端口。
 *
 * <p>只负责按 ID 保存与读取；格式识别与大小限制由应用服务在写入前完成。</p>
 */
public interface AttachmentStore {

    /**
     * 保存附件。
     *
     * @param fileName 原始文件名
     * @param content 二进制内容
     * @param format 已识别的图片格式
     * @return 元数据
     */
    AttachmentMetadata save(String fileName, byte[] content, ImageFormat format);

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
}
