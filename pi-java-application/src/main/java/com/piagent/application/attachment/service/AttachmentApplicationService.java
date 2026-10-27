package com.piagent.application.attachment.service;

import com.piagent.application.attachment.AttachmentContent;
import com.piagent.application.attachment.AttachmentMetadata;
import com.piagent.application.attachment.ImageFormat;
import com.piagent.application.attachment.ImageFormats;
import com.piagent.application.attachment.port.AttachmentStore;
import com.piagent.domain.session.model.message.ImageBlock;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.shared.Guard;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 附件应用服务。
 *
 * <p>统一收紧图片输入的边界：格式按文件头识别、单张不超过 5 MB、一条消息最多
 * 4 张；读取时把不存在的附件映射为明确的领域错误。</p>
 */
@Service
public class AttachmentApplicationService {

    /** 单条用户消息允许的图片数量。 */
    public static final int MAX_IMAGES_PER_MESSAGE = 4;

    /** 单张图片允许的最大字节数。 */
    public static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;

    private final AttachmentStore store;

    /**
     * 创建服务。
     *
     * @param store 附件存储
     */
    public AttachmentApplicationService(AttachmentStore store) {
        this.store = Objects.requireNonNull(store, "store 不能为空");
    }

    /**
     * 校验并保存图片。
     *
     * @param fileName 原始文件名
     * @param content 二进制内容
     * @return 元数据
     */
    public AttachmentMetadata upload(String fileName, byte[] content) {
        if (content == null || content.length == 0) {
            throw new DomainException(DomainErrorCode.ATTACHMENT_INVALID, "图片内容不能为空");
        }
        if (content.length > MAX_IMAGE_BYTES) {
            throw new DomainException(DomainErrorCode.ATTACHMENT_INVALID,
                    "图片不能超过 " + (MAX_IMAGE_BYTES / 1024 / 1024) + " MB");
        }
        ImageFormat format = ImageFormats.detect(content)
                .orElseThrow(() -> new DomainException(DomainErrorCode.ATTACHMENT_INVALID,
                        "不支持的图片格式，仅接受 PNG、JPEG、WebP 或 GIF"));
        return store.save(fileName, content, format);
    }

    /**
     * 读取附件内容。
     *
     * @param id 附件标识
     * @return 元数据与内容
     */
    public AttachmentContent load(String id) {
        return store.load(id).orElseThrow(() -> new DomainException(
                DomainErrorCode.ATTACHMENT_NOT_FOUND, "附件不存在: " + id));
    }

    /**
     * 把附件 ID 解析为图片内容块。
     *
     * @param ids 附件 ID，允许为空
     * @return 图片块
     */
    public List<ImageBlock> imageBlocks(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        Guard.require(ids.size() <= MAX_IMAGES_PER_MESSAGE, DomainErrorCode.INVALID_ARGUMENT,
                "一条消息最多 " + MAX_IMAGES_PER_MESSAGE + " 张图片");
        Set<String> seen = new HashSet<>();
        List<ImageBlock> blocks = new ArrayList<>(ids.size());
        for (String id : ids) {
            String value = Guard.notBlank(id, DomainErrorCode.INVALID_ARGUMENT, "附件 ID 不能为空");
            Guard.require(seen.add(value), DomainErrorCode.INVALID_ARGUMENT,
                    "同一张图片不能重复添加: " + value);
            AttachmentMetadata metadata = store.find(value)
                    .orElseThrow(() -> new DomainException(DomainErrorCode.ATTACHMENT_NOT_FOUND,
                            "附件不存在: " + value));
            blocks.add(new ImageBlock(metadata.id(), metadata.mediaType(), metadata.name()));
        }
        return List.copyOf(blocks);
    }
}
