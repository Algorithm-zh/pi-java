package com.piagent.application.testsupport;

import com.piagent.application.attachment.AttachmentContent;
import com.piagent.application.attachment.AttachmentMetadata;
import com.piagent.application.attachment.ImageFormat;
import com.piagent.application.attachment.port.AttachmentStore;
import com.piagent.domain.shared.IdGenerator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 内存附件存储，供应用层测试替换真实文件系统。
 */
public final class InMemoryAttachmentStore implements AttachmentStore {

    private final IdGenerator ids;
    private final Map<String, AttachmentContent> contents = new LinkedHashMap<>();

    /**
     * 创建内存存储。
     *
     * @param ids 标识生成器
     */
    public InMemoryAttachmentStore(IdGenerator ids) {
        this.ids = ids;
    }

    @Override
    public AttachmentMetadata save(String fileName, byte[] content, ImageFormat format) {
        String id = ids.newId();
        String name = fileName == null || fileName.isBlank()
                ? "image." + format.extension() : fileName;
        AttachmentMetadata metadata = new AttachmentMetadata(id, name, format.mediaType(),
                content.length);
        contents.put(id, new AttachmentContent(metadata, content));
        return metadata;
    }

    @Override
    public Optional<AttachmentMetadata> find(String id) {
        return Optional.ofNullable(contents.get(id)).map(AttachmentContent::metadata);
    }

    @Override
    public Optional<AttachmentContent> load(String id) {
        return Optional.ofNullable(contents.get(id));
    }
}
