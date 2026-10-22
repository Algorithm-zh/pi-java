package com.piagent.infrastructure.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.piagent.domain.context.SkillCatalog;
import com.piagent.domain.context.SkillDescriptor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 文件系统 Skill 源测试。
 *
 * <p>覆盖：frontmatter 解析、重名优先级、坏文件跳过、目录 mtime 失效、正文缓存、
 * 手动 refresh 与 AGENTS.md 读取。</p>
 */
class FileSystemSkillSourceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"),
            ZoneOffset.UTC);

    @TempDir
    Path root;

    @Test
    void discoversSkillsWithFrontmatter() throws IOException {
        writeSkill(root.resolve("code-review"), """
                ---
                name: code-review
                description: 按团队规范审查改动
                ---
                正文内容
                """);
        writeSkill(root.resolve("deploy"), "没有 frontmatter 的 skill");

        SkillCatalog catalog = source(List.of(root)).discover();

        assertThat(catalog.size()).isEqualTo(2);
        assertThat(catalog.find("code-review")).isPresent();
        SkillDescriptor deploy = catalog.find("deploy").orElseThrow();
        assertThat(deploy.description()).isEqualTo("没有 frontmatter 的 skill");
        assertThat(deploy.source()).isEqualTo(root.toAbsolutePath().normalize().toString());
    }

    @Test
    void earlierRootWinsOnDuplicateNames() throws IOException {
        Path first = Files.createDirectories(root.resolve("first"));
        Path second = Files.createDirectories(root.resolve("second"));
        writeSkill(first.resolve("dup"), "---\nname: dup\ndescription: 先出现的\n---\n");
        writeSkill(second.resolve("dup"), "---\nname: dup\ndescription: 后出现的\n---\n");

        SkillDescriptor dup = source(List.of(first, second)).discover().find("dup").orElseThrow();

        assertThat(dup.description()).isEqualTo("先出现的");
    }

    @Test
    void skipsDirectoriesWithoutSkillFile() throws IOException {
        Files.createDirectories(root.resolve("empty"));
        writeSkill(root.resolve("ok"), "---\nname: ok\ndescription: 正常\n---\n");

        SkillCatalog catalog = source(List.of(root)).discover();

        assertThat(catalog.all()).extracting(SkillDescriptor::name).containsExactly("ok");
    }

    @Test
    void ignoresMissingRoots() {
        SkillCatalog catalog = source(List.of(root.resolve("missing"))).discover();

        assertThat(catalog.isEmpty()).isTrue();
    }

    @Test
    void readsBodyWithoutFrontmatter() throws IOException {
        Path directory = writeSkill(root.resolve("reader"), """
                ---
                name: reader
                description: 读取测试
                ---
                # 标题
                正文一
                正文二
                """);
        FileSystemSkillSource source = source(List.of(root));
        SkillDescriptor skill = source.discover().find("reader").orElseThrow();

        assertThat(source.readContent(skill)).isEqualTo("# 标题\n正文一\n正文二");
        assertThat(directory.getFileName()).isEqualTo(Path.of("reader"));
    }

    @Test
    void rescansWhenDirectoryChanges() throws IOException {
        writeSkill(root.resolve("one"), "---\nname: one\ndescription: 一\n---\n");
        FileSystemSkillSource source = source(List.of(root));
        assertThat(source.discover().size()).isEqualTo(1);

        writeSkill(root.resolve("two"), "---\nname: two\ndescription: 二\n---\n");
        Files.setLastModifiedTime(root, java.nio.file.attribute.FileTime.fromMillis(
                System.currentTimeMillis() + 5_000));

        assertThat(source.discover().size()).isEqualTo(2);
    }

    @Test
    void refreshClearsCache() throws IOException {
        writeSkill(root.resolve("one"), "---\nname: one\ndescription: 一\n---\n");
        FileSystemSkillSource source = source(List.of(root));
        assertThat(source.discover().size()).isEqualTo(1);

        writeSkill(root.resolve("two"), "---\nname: two\ndescription: 二\n---\n");
        source.refresh();

        assertThat(source.discover().size()).isEqualTo(2);
    }

    @Test
    void reReadsContentWhenFileChanges() throws IOException {
        Path directory = writeSkill(root.resolve("changing"), "---\nname: changing\ndescription: d\n---\n旧正文");
        FileSystemSkillSource source = source(List.of(root));
        SkillDescriptor skill = source.discover().find("changing").orElseThrow();
        assertThat(source.readContent(skill)).isEqualTo("旧正文");

        Files.writeString(directory.resolve(SkillDirectoryScanner.SKILL_FILE),
                "---\nname: changing\ndescription: d\n---\n新正文", StandardCharsets.UTF_8);

        assertThat(source.readContent(skill)).isEqualTo("新正文");
    }

    @Test
    void agentsDocumentIsReadOnlyFromRoot() throws IOException {
        Files.writeString(root.resolve(AgentsDocumentSource.FILE_NAME), "  项目约定：先写测试  ");
        AgentsDocumentSource source = new AgentsDocumentSource();

        assertThat(source.readAgentsDocument(root)).contains("项目约定：先写测试");
        assertThat(source.readAgentsDocument(root.resolve("nested"))).isEmpty();
        assertThat(source.readAgentsDocument(null)).isEmpty();
    }

    @Test
    void agentsDocumentIgnoresOversizedFile() throws IOException {
        Path dir = Files.createDirectories(root.resolve("big"));
        Files.writeString(dir.resolve(AgentsDocumentSource.FILE_NAME),
                "x".repeat((int) AgentsDocumentSource.MAX_BYTES + 10));

        assertThat(new AgentsDocumentSource().readAgentsDocument(dir)).isEmpty();
    }

    @Test
    void resolvesHomePrefixedDirectories() {
        String home = System.getProperty("user.home");

        assertThat(SkillDirectoryResolver.expand("~/skills"))
                .isEqualTo(Path.of(home, "skills").toAbsolutePath().normalize());
        assertThat(SkillDirectoryResolver.expand("~"))
                .isEqualTo(Path.of(home).toAbsolutePath().normalize());
        assertThat(SkillDirectoryResolver.expand("  ")).isNull();
        assertThat(SkillDirectoryResolver.expand(null)).isNull();
    }

    private FileSystemSkillSource source(List<Path> roots) {
        return new FileSystemSkillSource(roots, Duration.ofMinutes(5), CLOCK);
    }

    private Path writeSkill(Path directory, String content) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(SkillDirectoryScanner.SKILL_FILE), content,
                StandardCharsets.UTF_8);
        return directory;
    }
}
