package com.piagent.infrastructure.attachment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.piagent.application.attachment.AttachmentContent;
import com.piagent.application.attachment.AttachmentMetadata;
import com.piagent.application.attachment.ImageFormat;
import com.piagent.application.attachment.port.AttachmentStore;
import com.piagent.application.identity.AuthContext;
import com.piagent.domain.identity.model.TenantId;
import com.piagent.domain.shared.IdGenerator;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 基于文件系统的多租户附件存储。
 *
 * <p>默认租户沿用根目录的扁平布局，存量图片升级后无需迁移；其他租户使用
 * {@code <根目录>/<租户 ID>/} 子目录。每次操作按当前认证上下文选择目录，
 * 因此跨租户读取表现为“附件不存在”。ID 必须是 ULID，天然排除路径穿越。</p>
 */
public final class FileSystemAttachmentStore implements AttachmentStore {

    private final Path root;
    private final IdGenerator idGenerator;
    private final ObjectMapper mapper;
    private final ConcurrentMap<String, TenantAttachmentScope> scopes = new ConcurrentHashMap<>();

    /**
     * 创建存储。
     *
     * @param root 附件根目录
     * @param idGenerator 标识生成器
     * @param mapper Jackson 映射器
     */
    public FileSystemAttachmentStore(Path root, IdGenerator idGenerator, ObjectMapper mapper) {
        this.root = Objects.requireNonNull(root, "root 不能为空").toAbsolutePath().normalize();
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator 不能为空");
        this.mapper = Objects.requireNonNull(mapper, "mapper 不能为空");
    }

    @Override
    public AttachmentMetadata save(String fileName, byte[] content, ImageFormat format,
            String sha256) {
        return scope().save(fileName, content, format, sha256);
    }

    @Override
    public Optional<AttachmentMetadata> find(String id) {
        return scope().find(id);
    }

    @Override
    public Optional<AttachmentContent> load(String id) {
        return scope().load(id);
    }

    @Override
    public Optional<AttachmentMetadata> findByDigest(String sha256) {
        return scope().findByDigest(sha256);
    }

    @Override
    public List<StoredAttachment> list() {
        return scope().list();
    }

    @Override
    public long totalBytes() {
        return scope().totalBytes();
    }

    @Override
    public boolean delete(String id) {
        return scope().delete(id);
    }

    private TenantAttachmentScope scope() {
        TenantId tenantId = AuthContext.tenantId();
        String key = tenantId.toString();
        return scopes.computeIfAbsent(key, ignored ->
                new TenantAttachmentScope(directory(tenantId), idGenerator, mapper));
    }

    private Path directory(TenantId tenantId) {
        return tenantId.isDefault() ? root : root.resolve(tenantId.toString());
    }
}
