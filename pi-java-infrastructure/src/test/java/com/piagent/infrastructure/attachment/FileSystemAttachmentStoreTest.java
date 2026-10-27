package com.piagent.infrastructure.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.piagent.application.attachment.AttachmentContent;
import com.piagent.application.attachment.AttachmentMetadata;
import com.piagent.application.attachment.ImageFormat;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.shared.IdGenerator;
import com.piagent.domain.shared.Ulid;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 文件系统附件存储测试。
 *
 * <p>无数据库、无网络；重点覆盖文件名收敛、元数据先行的提交语义、摘要去重、
 * 列表删除，以及非法 ID 与损坏元数据的错误边界。</p>
 */
class FileSystemAttachmentStoreTest {

    private static final ImageFormat PNG_FORMAT = new ImageFormat("image/png", "png");
    private static final byte[] PNG = {
        (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0
    };

    @TempDir
    private Path temp;

    private final AtomicInteger sequence = new AtomicInteger();
    private final IdGenerator ids =
            () -> String.format("01JAVA0000%016d", sequence.incrementAndGet());

    @Test
    void savesAndReadsBackContent() {
        FileSystemAttachmentStore store = store();

        AttachmentMetadata metadata = store.save("C:\\Users\\me\\shot.png", PNG, PNG_FORMAT,
                digest("shot"));

        assertThat(Ulid.parse(metadata.id()).value()).isEqualTo(metadata.id());
        assertThat(metadata.name()).isEqualTo("shot.png");
        assertThat(metadata.mediaType()).isEqualTo("image/png");
        assertThat(metadata.size()).isEqualTo(PNG.length);
        AttachmentContent content = store.load(metadata.id()).orElseThrow();
        assertThat(content.metadata()).isEqualTo(metadata);
        assertThat(content.content()).containsExactly(PNG);
        assertThat(store.find(metadata.id())).contains(metadata);
        assertThat(store.findByDigest(digest("shot"))).contains(metadata);
    }

    @Test
    void defaultsBlankNameAndTruncatesLongName() throws IOException {
        FileSystemAttachmentStore store = store();

        AttachmentMetadata blank = store.save(null, PNG, PNG_FORMAT, digest("blank"));
        AttachmentMetadata longName = store.save("a".repeat(200) + ".png", PNG, PNG_FORMAT,
                digest("long"));

        assertThat(blank.name()).isEqualTo("image.png");
        assertThat(longName.name()).hasSize(120);
        assertThat(Files.isRegularFile(temp.resolve("attachments").resolve(blank.id() + ".bin")))
                .isTrue();
    }

    @Test
    void deduplicatesSameDigestWithoutWritingTwice() {
        FileSystemAttachmentStore store = store();

        AttachmentMetadata first = store.save("one.png", PNG, PNG_FORMAT, digest("same"));
        AttachmentMetadata second = store.save("copy.png", PNG, PNG_FORMAT, digest("same"));

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.name()).isEqualTo("one.png");
        assertThat(store.list()).hasSize(1);
        assertThat(store.totalBytes()).isEqualTo(PNG.length);
    }

    @Test
    void listsDeletesAndRemovesDigestFromIndex() {
        FileSystemAttachmentStore store = store();
        AttachmentMetadata first = store.save("one.png", PNG, PNG_FORMAT, digest("one"));
        AttachmentMetadata second = store.save("two.png", PNG, PNG_FORMAT, digest("two"));

        assertThat(store.list()).hasSize(2);
        assertThat(store.list().get(0).createdAt()).isNotNull();
        assertThat(store.totalBytes()).isEqualTo(PNG.length * 2L);

        assertThat(store.delete(first.id())).isTrue();
        assertThat(store.delete(first.id())).isFalse();
        assertThat(store.find(first.id())).isEmpty();
        assertThat(store.findByDigest(digest("one"))).isEmpty();
        assertThat(store.list()).hasSize(1);
        assertThat(store.totalBytes()).isEqualTo(second.size());
    }

    @Test
    void rebuildsDigestIndexOnNextInstance() {
        FileSystemAttachmentStore first = store();
        AttachmentMetadata metadata = first.save("one.png", PNG, PNG_FORMAT, digest("one"));

        FileSystemAttachmentStore second = store();

        assertThat(second.findByDigest(digest("one"))).contains(metadata);
        assertThat(second.totalBytes()).isEqualTo(PNG.length);
    }

    @Test
    void ignoresInvalidDigestsAndLegacyMetadataWithoutDigest() throws IOException {
        FileSystemAttachmentStore store = store();
        AttachmentMetadata metadata = store.save("one.png", PNG, PNG_FORMAT, digest("one"));
        Path meta = temp.resolve("attachments").resolve(metadata.id() + ".json");
        Files.writeString(meta, """
                {"id":"%s","name":"one.png","mediaType":"image/png","size":%d}
                """.formatted(metadata.id(), PNG.length));

        assertThat(store.findByDigest("../bad")).isEmpty();
        assertThat(store.findByDigest(null)).isEmpty();
        assertThat(store.find(metadata.id())).isPresent();
    }

    @Test
    void rejectsInvalidIdentifiersWithoutTouchingDisk() {
        FileSystemAttachmentStore store = store();

        assertThat(store.find(null)).isEmpty();
        assertThat(store.find("  ")).isEmpty();
        assertThat(store.find("../../etc/passwd")).isEmpty();
        assertThat(store.load("../../etc/passwd")).isEmpty();
        assertThat(store.delete("../../etc/passwd")).isFalse();
    }

    @Test
    void missingBinaryReturnsEmptyButKeepsMetadata() throws IOException {
        FileSystemAttachmentStore store = store();
        AttachmentMetadata metadata = store.save("shot.png", PNG, PNG_FORMAT, digest("shot"));

        Files.delete(temp.resolve("attachments").resolve(metadata.id() + ".bin"));

        assertThat(store.find(metadata.id())).contains(metadata);
        assertThat(store.load(metadata.id())).isEmpty();
    }

    @Test
    void corruptMetadataFailsExplicitly() throws IOException {
        FileSystemAttachmentStore store = store();
        AttachmentMetadata metadata = store.save("shot.png", PNG, PNG_FORMAT, digest("shot"));
        Files.writeString(temp.resolve("attachments").resolve(metadata.id() + ".json"), "{broken");

        assertThatThrownBy(() -> store.find(metadata.id()))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.ATTACHMENT_STORE_FAILED));
    }

    private FileSystemAttachmentStore store() {
        return new FileSystemAttachmentStore(temp.resolve("attachments"), ids, new ObjectMapper());
    }

    private static String digest(String seed) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(seed.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
