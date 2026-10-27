package com.piagent.infrastructure.attachment;

import static com.piagent.infrastructure.attachment.AttachmentMetadataFile.BINARY_SUFFIX;
import static com.piagent.infrastructure.attachment.AttachmentMetadataFile.DIGEST;
import static com.piagent.infrastructure.attachment.AttachmentMetadataFile.META_SUFFIX;
import static com.piagent.infrastructure.attachment.AttachmentMetadataFile.idOf;
import static com.piagent.infrastructure.attachment.AttachmentMetadataFile.json;
import static com.piagent.infrastructure.attachment.AttachmentMetadataFile.lastModified;
import static com.piagent.infrastructure.attachment.AttachmentMetadataFile.read;
import static com.piagent.infrastructure.attachment.AttachmentMetadataFile.readDigest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.piagent.application.attachment.AttachmentContent;
import com.piagent.application.attachment.AttachmentMetadata;
import com.piagent.application.attachment.ImageFormat;
import com.piagent.application.attachment.port.AttachmentStore;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.shared.IdGenerator;
import com.piagent.domain.shared.Ulid;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于文件系统的附件存储。
 *
 * <p>每个附件由 {@code <id>.bin} 与 {@code <id>.json} 两个文件组成；先写二进制，
 * 最后写元数据，元数据存在即视为提交完成。元数据记录内容摘要，摘要相同的上传
 * 直接复用已有附件。ID 必须是 ULID，天然排除路径穿越。</p>
 */
public final class FileSystemAttachmentStore implements AttachmentStore {

    private static final Logger LOG = LoggerFactory.getLogger(FileSystemAttachmentStore.class);
    private static final int MAX_NAME_LENGTH = 120;

    private final Path root;
    private final IdGenerator idGenerator;
    private final ObjectMapper mapper;
    private final Map<String, String> digests = new HashMap<>();
    private boolean digestsLoaded;

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
        Objects.requireNonNull(content, "content 不能为空");
        Objects.requireNonNull(format, "format 不能为空");
        Objects.requireNonNull(sha256, "sha256 不能为空");
        Map<String, String> index = digests();
        synchronized (index) {
            String existingId = index.get(sha256);
            if (existingId != null) {
                Optional<AttachmentMetadata> existing = find(existingId);
                if (existing.isPresent()) {
                    return existing.get();
                }
                index.remove(sha256);
            }
            String id = Ulid.parse(idGenerator.newId()).value();
            AttachmentMetadata metadata = new AttachmentMetadata(id,
                    displayName(fileName, format.extension()), format.mediaType(), content.length);
            write(id, content, metaJson(metadata, sha256));
            index.put(sha256, id);
            return metadata;
        }
    }

    @Override
    public Optional<AttachmentMetadata> find(String id) {
        Optional<String> safeId = safeId(id);
        if (safeId.isEmpty()) {
            return Optional.empty();
        }
        Path metaPath = root.resolve(safeId.get() + META_SUFFIX);
        return Files.isRegularFile(metaPath) ? Optional.of(read(mapper, metaPath)) : Optional.empty();
    }

    @Override
    public Optional<AttachmentContent> load(String id) {
        Optional<AttachmentMetadata> metadata = find(id);
        if (metadata.isEmpty()) {
            return Optional.empty();
        }
        Path binary = root.resolve(metadata.get().id() + BINARY_SUFFIX);
        try {
            return Optional.of(new AttachmentContent(metadata.get(), Files.readAllBytes(binary)));
        } catch (java.nio.file.NoSuchFileException error) {
            return Optional.empty();
        } catch (IOException error) {
            throw new DomainException(DomainErrorCode.ATTACHMENT_STORE_FAILED,
                    "读取附件失败: " + metadata.get().id(), error);
        }
    }

    @Override
    public Optional<AttachmentMetadata> findByDigest(String sha256) {
        if (sha256 == null || !DIGEST.matcher(sha256).matches()) {
            return Optional.empty();
        }
        Map<String, String> index = digests();
        String id;
        synchronized (index) {
            id = index.get(sha256);
        }
        if (id == null) {
            return Optional.empty();
        }
        Optional<AttachmentMetadata> metadata = find(id);
        if (metadata.isEmpty()) {
            synchronized (index) {
                index.remove(sha256);
            }
        }
        return metadata;
    }

    @Override
    public List<StoredAttachment> list() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<StoredAttachment> items = new ArrayList<>();
        for (Path meta : metadataFiles()) {
            try {
                items.add(new StoredAttachment(read(mapper, meta), lastModified(meta)));
            } catch (RuntimeException error) {
                LOG.warn("跳过损坏的附件元数据 {}: {}", meta.getFileName(), error.getMessage());
            }
        }
        return List.copyOf(items);
    }

    @Override
    public long totalBytes() {
        if (!Files.isDirectory(root)) {
            return 0;
        }
        try (Stream<Path> files = Files.list(root)) {
            return files.filter(path -> path.getFileName().toString().endsWith(BINARY_SUFFIX))
                    .mapToLong(AttachmentMetadataFile::sizeOf)
                    .sum();
        } catch (IOException error) {
            throw new DomainException(DomainErrorCode.ATTACHMENT_STORE_FAILED,
                    "统计附件大小失败", error);
        }
    }

    @Override
    public boolean delete(String id) {
        Optional<String> safeId = safeId(id);
        if (safeId.isEmpty()) {
            return false;
        }
        String value = safeId.get();
        Path meta = root.resolve(value + META_SUFFIX);
        Path binary = root.resolve(value + BINARY_SUFFIX);
        Map<String, String> index = digests();
        synchronized (index) {
            readDigest(mapper, meta).ifPresent(digest -> index.remove(digest, value));
            try {
                boolean removedMeta = Files.deleteIfExists(meta);
                boolean removedBinary = Files.deleteIfExists(binary);
                return removedMeta || removedBinary;
            } catch (IOException error) {
                throw new DomainException(DomainErrorCode.ATTACHMENT_STORE_FAILED,
                        "删除附件失败: " + value, error);
            }
        }
    }

    private void write(String id, byte[] content, byte[] metaJson) {
        Path binary = root.resolve(id + BINARY_SUFFIX);
        Path meta = root.resolve(id + META_SUFFIX);
        try {
            Files.createDirectories(root);
            try {
                writeAtomically(binary, content);
                writeAtomically(meta, metaJson);
            } catch (IOException error) {
                Files.deleteIfExists(binary);
                Files.deleteIfExists(meta);
                throw error;
            }
        } catch (IOException error) {
            throw new DomainException(DomainErrorCode.ATTACHMENT_STORE_FAILED,
                    "保存附件失败: " + id, error);
        }
    }

    private byte[] metaJson(AttachmentMetadata metadata, String sha256) {
        try {
            return json(mapper, metadata, sha256);
        } catch (IOException error) {
            throw new DomainException(DomainErrorCode.ATTACHMENT_STORE_FAILED,
                    "序列化附件元数据失败: " + metadata.id(), error);
        }
    }

    private Map<String, String> digests() {
        synchronized (digests) {
            if (digestsLoaded) {
                return digests;
            }
            digestsLoaded = true;
            if (!Files.isDirectory(root)) {
                return digests;
            }
            for (Path meta : metadataFiles()) {
                readDigest(mapper, meta).ifPresent(digest ->
                        digests.putIfAbsent(digest, idOf(meta)));
            }
            return digests;
        }
    }

    private List<Path> metadataFiles() {
        try (Stream<Path> files = Files.list(root)) {
            return files.filter(AttachmentMetadataFile::isMetadata).toList();
        } catch (IOException error) {
            LOG.warn("读取附件目录失败 {}: {}", root, error.getMessage());
            return List.of();
        }
    }

    private void writeAtomically(Path target, byte[] content) throws IOException {
        Path temp = Files.createTempFile(root, "upload-", ".tmp");
        try {
            Files.write(temp, content);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static Optional<String> safeId(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Ulid.parse(id).value());
        } catch (IllegalArgumentException error) {
            return Optional.empty();
        }
    }

    private static String displayName(String raw, String extension) {
        String value = raw == null ? "" : raw.replace('\\', '/');
        int slash = value.lastIndexOf('/');
        if (slash >= 0) {
            value = value.substring(slash + 1);
        }
        value = value.strip();
        if (value.isEmpty()) {
            value = "image." + extension;
        }
        return value.length() <= MAX_NAME_LENGTH ? value : value.substring(0, MAX_NAME_LENGTH);
    }
}
