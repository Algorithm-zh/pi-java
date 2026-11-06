package com.piagent.infrastructure.attachment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.piagent.application.attachment.AttachmentContent;
import com.piagent.application.attachment.AttachmentMetadata;
import com.piagent.application.attachment.ImageFormat;
import com.piagent.application.attachment.port.AttachmentStore;
import com.piagent.domain.shared.IdGenerator;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 基于文件系统的附件存储。
 *
 * <p>所有附件保存在同一个根目录下，ID 必须是 ULID，天然排除路径穿越。</p>
 */
public final class FileSystemAttachmentStore implements AttachmentStore {

    private final AttachmentScope scope;

    /**
     * 创建存储。
     *
     * @param root 附件根目录
     * @param idGenerator 标识生成器
     * @param mapper Jackson 映射器
     */
    public FileSystemAttachmentStore(Path root, IdGenerator idGenerator, ObjectMapper mapper) {
        Path directory = Objects.requireNonNull(root, "root 不能为空").toAbsolutePath().normalize();
        this.scope = new AttachmentScope(directory, idGenerator, mapper);
    }

    @Override
    public AttachmentMetadata save(String fileName, byte[] content, ImageFormat format,
            String sha256) {
        return scope.save(fileName, content, format, sha256);
    }

    @Override
    public Optional<AttachmentMetadata> find(String id) {
        return scope.find(id);
    }

    @Override
    public Optional<AttachmentContent> load(String id) {
        return scope.load(id);
    }

    @Override
    public Optional<AttachmentMetadata> findByDigest(String sha256) {
        return scope.findByDigest(sha256);
    }

    @Override
    public List<StoredAttachment> list() {
        return scope.list();
    }

    @Override
    public long totalBytes() {
        return scope.totalBytes();
    }

    @Override
    public boolean delete(String id) {
        return scope.delete(id);
    }
}
