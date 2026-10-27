package com.piagent.infrastructure.attachment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.piagent.application.attachment.AttachmentMetadata;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 附件元数据文件的读写与命名约定。
 *
 * <p>一个附件对应 {@code <id>.bin} 与 {@code <id>.json}；元数据最后写入，作为
 * 提交标记。摘要字段缺失或损坏时只影响去重与回收判断，不阻塞读取。</p>
 */
final class AttachmentMetadataFile {

    static final String BINARY_SUFFIX = ".bin";
    static final String META_SUFFIX = ".json";
    static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");

    private static final Logger LOG = LoggerFactory.getLogger(AttachmentMetadataFile.class);

    private AttachmentMetadataFile() {
    }

    static boolean isMetadata(Path path) {
        return path.getFileName().toString().endsWith(META_SUFFIX);
    }

    static String idOf(Path metaPath) {
        String name = metaPath.getFileName().toString();
        return name.substring(0, name.length() - META_SUFFIX.length());
    }

    static AttachmentMetadata read(ObjectMapper mapper, Path metaPath) {
        try {
            JsonNode node = mapper.readTree(Files.readAllBytes(metaPath));
            return new AttachmentMetadata(text(node, "id"), text(node, "name"),
                    text(node, "mediaType"), node.path("size").asLong());
        } catch (IOException | RuntimeException error) {
            throw new DomainException(DomainErrorCode.ATTACHMENT_STORE_FAILED,
                    "附件元数据损坏: " + metaPath.getFileName(), error);
        }
    }

    static Optional<String> readDigest(ObjectMapper mapper, Path metaPath) {
        try {
            JsonNode node = mapper.readTree(Files.readAllBytes(metaPath));
            JsonNode digest = node.get("sha256");
            return digest != null && digest.isTextual() && DIGEST.matcher(digest.asText()).matches()
                    ? Optional.of(digest.asText()) : Optional.empty();
        } catch (IOException | RuntimeException error) {
            LOG.warn("读取附件摘要失败 {}: {}", metaPath.getFileName(), error.getMessage());
            return Optional.empty();
        }
    }

    static byte[] json(ObjectMapper mapper, AttachmentMetadata metadata, String sha256)
            throws IOException {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", metadata.id());
        node.put("name", metadata.name());
        node.put("mediaType", metadata.mediaType());
        node.put("size", metadata.size());
        node.put("sha256", sha256);
        return mapper.writeValueAsBytes(node);
    }

    static Instant lastModified(Path path) {
        try {
            FileTime time = Files.getLastModifiedTime(path);
            return time.toInstant();
        } catch (IOException error) {
            return Instant.EPOCH;
        }
    }

    static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException error) {
            return 0;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("缺少字段: " + field);
        }
        return value.asText();
    }
}
