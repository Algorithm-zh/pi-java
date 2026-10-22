package com.piagent.infrastructure.context;

import com.piagent.domain.context.SkillDescriptor;
import com.piagent.domain.context.SkillFrontmatter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 从单个目录读取 skill。
 *
 * <p>目录约定（与 pi 一致，保持极简）：</p>
 * <pre>
 * skills/
 *   code-review/SKILL.md
 *   deploy/SKILL.md
 * </pre>
 *
 * <p>每个子目录最多扫描一层；{@code SKILL.md} 缺失或不可读时静默跳过该目录，
 * 由上层统计“扫描了多少、跳过了多少”，而不是让一条坏文件中断整个目录。</p>
 */
final class SkillDirectoryScanner {

    /** Skill 文件名。 */
    static final String SKILL_FILE = "SKILL.md";

    /** 单个 SKILL.md 的最大字节数，超过视为异常文件直接跳过。 */
    static final long MAX_FILE_BYTES = 256L * 1024L;

    /**
     * 扫描结果。
     *
     * @param skills 成功解析的 skill
     * @param skipped 跳过的目录数
     */
    record ScanResult(List<SkillDescriptor> skills, int skipped) {
    }

    /**
     * 扫描目录。
     *
     * @param directory skill 根目录
     * @param source 来源标识，写入描述用于展示
     * @return 扫描结果
     */
    ScanResult scan(Path directory, String source) {
        if (!Files.isDirectory(directory)) {
            return new ScanResult(List.of(), 0);
        }
        List<Path> candidates;
        try (Stream<Path> children = Files.list(directory)) {
            candidates = children
                    .filter(Files::isDirectory)
                    .sorted()
                    .toList();
        } catch (IOException error) {
            return new ScanResult(List.of(), 0);
        }
        java.util.ArrayList<SkillDescriptor> skills = new ArrayList<>();
        int skipped = 0;
        for (Path child : candidates) {
            Optional<SkillDescriptor> descriptor = read(child, source);
            if (descriptor.isPresent()) {
                skills.add(descriptor.get());
            } else {
                skipped++;
            }
        }
        return new ScanResult(List.copyOf(skills), skipped);
    }

    private Optional<SkillDescriptor> read(Path skillDirectory, String source) {
        Path file = skillDirectory.resolve(SKILL_FILE);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            if (Files.size(file) > MAX_FILE_BYTES) {
                return Optional.empty();
            }
            String content = Files.readString(file, StandardCharsets.UTF_8);
            SkillFrontmatter.Parsed parsed = SkillFrontmatter.parse(content,
                    skillDirectory.getFileName().toString());
            return Optional.of(new SkillDescriptor(parsed.name(), parsed.description(), file, source));
        } catch (IOException | RuntimeException error) {
            return Optional.empty();
        }
    }
}
