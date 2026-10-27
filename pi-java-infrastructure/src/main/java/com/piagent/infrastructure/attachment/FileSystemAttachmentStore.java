package com.piagent.infrastructure.attachment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.Objects;
import java.util.Optional;

/**
 * 基于文件系统的附件存储。
 *
 * <p>每个附件由 {@code <id>.bin} 与 {@code <id>.json} 两个文件组成；先写二进制，
 * 最后写元数据，元数据存在即视为提交完成。ID 必须是 ULID，天然排除路径穿越。</p>
 */
public final class FileSystemAttachmentStore implements AttachmentStore {

    private static final int MAX_NAME_LENGTH = 120;
    private static final String BINARY_SUFFIX = ".bin";
    private static final String META_SUFFIX = ".json";

    private final Path root;
    private final IdGenerator idGenerator;
    private final ObjectMapper mapper;

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
    public AttachmentMetadata save(String fileName, byte[] content, ImageFormat format) {
        Objects.requireNonNull(content, "content 不能为空");
        Objects.requireNonNull(format, "format 不能为空");
        String id = Ulid.parse(idGenerator.newId()).value();
        AttachmentMetadata metadata = new AttachmentMetadata(id,
                displayName(fileName, format.extension()), format.mediaType(), content.length);
        try {
            Files.createDirectories(root);
            writeAtomically(root.resolve(id + BINARY_SUFFIX), content);
            writeAtomically(root.resolve(id + META_SUFFIX), metaJson(metadata));
        } catch (IOException error) {
            throw new DomainException(DomainErrorCode.ATTACHMENT_STORE_FAILED,
                    "保存附件失败: " + id, error);
        }
        return metadata;
    }

    @Override
    public Optional<AttachmentMetadata> find(String id) {
        Optional<String> safeId = safeId(id);
        if (safeId.isEmpty()) {
            return Optional.empty();
        }
        Path metaPath = root.resolve(safeId.get() + META_SUFFIX);
        if (!Files.isRegularFile(metaPath)) {
            return Optional.empty();
        }
        try {
            JsonNode node = mapper.readTree(Files.readAllBytes(metaPath));
            return Optional.of(new AttachmentMetadata(
                    text(node, "id"), text(node, "name"), text(node, "mediaType"),
                    node.path("size").asLong()));
        } catch (IOException | RuntimeException error) {
            throw new DomainException(DomainErrorCode.ATTACHMENT_STORE_FAILED,
                    "附件元数据损坏: " + safeId.get(), error);
        }
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

    private byte[] metaJson(AttachmentMetadata metadata) throws IOException {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", metadata.id());
        node.put("name", metadata.name());
        node.put("mediaType", metadata.mediaType());
        node.put("size", metadata.size());
        return mapper.writeValueAsBytes(node);
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

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("缺少字段: " + field);
        }
        return value.asText();
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
