package com.piagent.application.testsupport;

import com.piagent.application.attachment.AttachmentContent;
import com.piagent.application.attachment.AttachmentMetadata;
import com.piagent.application.attachment.ImageFormat;
import com.piagent.application.attachment.port.AttachmentStore;
import com.piagent.domain.shared.IdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 内存附件存储，供应用层测试替换真实文件系统。
 */
public final class InMemoryAttachmentStore implements AttachmentStore {

    private final IdGenerator ids;
    private final Clock clock;
    private final Map<String, AttachmentContent> contents = new LinkedHashMap<>();
    private final Map<String, Instant> createdAt = new LinkedHashMap<>();
    private final Map<String, String> byDigest = new LinkedHashMap<>();

    /**
     * 创建内存存储，使用系统时钟。
     *
     * @param ids 标识生成器
     */
    public InMemoryAttachmentStore(IdGenerator ids) {
        this(ids, Clock.systemUTC());
    }

    /**
     * 创建内存存储。
     *
     * @param ids 标识生成器
     * @param clock 时钟
     */
    public InMemoryAttachmentStore(IdGenerator ids, Clock clock) {
        this.ids = ids;
        this.clock = clock;
    }

    @Override
    public AttachmentMetadata save(String fileName, byte[] content, ImageFormat format,
            String sha256) {
        String existing = byDigest.get(sha256);
        if (existing != null) {
            return contents.get(existing).metadata();
        }
        String id = ids.newId();
        String name = fileName == null || fileName.isBlank()
                ? "image." + format.extension() : fileName;
        AttachmentMetadata metadata = new AttachmentMetadata(id, name, format.mediaType(),
                content.length);
        contents.put(id, new AttachmentContent(metadata, content));
        createdAt.put(id, clock.instant());
        byDigest.put(sha256, id);
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

    @Override
    public Optional<AttachmentMetadata> findByDigest(String sha256) {
        String id = byDigest.get(sha256);
        return id == null ? Optional.empty() : find(id);
    }

    @Override
    public List<StoredAttachment> list() {
        List<StoredAttachment> items = new ArrayList<>(contents.size());
        for (AttachmentContent content : contents.values()) {
            items.add(new StoredAttachment(content.metadata(),
                    createdAt.get(content.metadata().id())));
        }
        return List.copyOf(items);
    }

    @Override
    public long totalBytes() {
        return contents.values().stream().mapToLong(item -> item.content().length).sum();
    }

    @Override
    public boolean delete(String id) {
        AttachmentContent removed = contents.remove(id);
        if (removed == null) {
            return false;
        }
        createdAt.remove(id);
        byDigest.values().removeIf(id::equals);
        return true;
    }
}
