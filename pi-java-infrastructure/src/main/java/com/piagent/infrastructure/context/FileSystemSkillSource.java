package com.piagent.infrastructure.context;

import com.piagent.domain.context.SkillCatalog;
import com.piagent.domain.context.SkillDescriptor;
import com.piagent.domain.context.SkillFrontmatter;
import com.piagent.domain.context.port.SkillSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文件系统 Skill 源。
 *
 * <p>扫描多个根目录，<b>先出现的目录优先</b>：用户目录在前、内置目录在后，
 * 重名时保留先生成的那个，并在描述中标注来源，便于排查“为什么不是我的版本生效”。</p>
 *
 * <p>缓存策略（对应 ADR 0002，不引入 Redis）：</p>
 * <ul>
 *   <li>时间失效：目录快照最多缓存 {@code ttl}（默认 5 分钟）；</li>
 *   <li>内容失效：TTL 内命中时仍校验根目录的 mtime，目录被增删子目录时立即重扫；
 *       SKILL.md 正文按“路径 + mtime + 大小”缓存，避免每次读取都重新解析；</li>
 *   <li>手动失效：{@link #refresh()} 清空全部缓存，供 {@code GET /skills?refresh=true} 使用。</li>
 * </ul>
 *
 * <p>扫描失败不抛异常：某个根目录不可读时跳过它，返回其余目录的并集。</p>
 */
public final class FileSystemSkillSource implements SkillSource {

    /** 默认缓存时间。 */
    public static final Duration DEFAULT_TTL = Duration.ofMinutes(5);

    private final List<Path> roots;
    private final Duration ttl;
    private final Clock clock;
    private final SkillDirectoryScanner scanner = new SkillDirectoryScanner();
    private final Map<Path, CachedCatalog> catalogs = new ConcurrentHashMap<>();
    private final Map<Path, CachedContent> contents = new ConcurrentHashMap<>();

    /**
     * 创建 Skill 源。
     *
     * @param roots 根目录，按优先级从高到低
     * @param ttl 目录快照缓存时间
     * @param clock 时钟
     */
    public FileSystemSkillSource(List<Path> roots, Duration ttl, Clock clock) {
        this.roots = List.copyOf(roots);
        this.ttl = ttl;
        this.clock = clock;
    }

    /**
     * 创建使用默认缓存的 Skill 源。
     *
     * @param roots 根目录
     * @param clock 时钟
     */
    public FileSystemSkillSource(List<Path> roots, Clock clock) {
        this(roots, DEFAULT_TTL, clock);
    }

    @Override
    public SkillCatalog discover() {
        Instant now = clock.instant();
        Map<String, SkillDescriptor> merged = new LinkedHashMap<>();
        int skipped = 0;
        for (Path root : roots) {
            SkillDirectoryScanner.ScanResult result = scanRoot(root, now);
            skipped += result.skipped();
            for (SkillDescriptor skill : result.skills()) {
                merged.putIfAbsent(skill.name(), skill);
            }
        }
        if (skipped > 0) {
            // 跳过数量只用于日志观测，不进入目录；异常文件不应让目录整体失败。
            System.getLogger(FileSystemSkillSource.class.getName())
                    .log(System.Logger.Level.DEBUG, "跳过 {0} 个无法解析的 skill 目录", skipped);
        }
        return merged.isEmpty() ? SkillCatalog.empty() : new SkillCatalog(new ArrayList<>(merged.values()));
    }

    @Override
    public String readContent(SkillDescriptor skill) {
        Path path = skill.path();
        try {
            Optional<CachedContent> cached = contentOf(path);
            if (cached.isPresent()) {
                return cached.get().body();
            }
            String content = Files.readString(path, StandardCharsets.UTF_8);
            String body = SkillFrontmatter.parse(content,
                    path.getParent().getFileName().toString()).body();
            contents.put(path, new CachedContent(mtimeOf(path), sizeOf(path), body));
            return body;
        } catch (IOException error) {
            throw new IllegalStateException("无法读取 skill 文件: " + path, error);
        }
    }

    @Override
    public void refresh() {
        catalogs.clear();
        contents.clear();
    }

    private Optional<CachedContent> contentOf(Path path) {
        CachedContent cached = contents.get(path);
        if (cached == null) {
            return Optional.empty();
        }
        if (cached.mtime().equals(mtimeOf(path)) && cached.size() == sizeOf(path)) {
            return Optional.of(cached);
        }
        contents.remove(path);
        return Optional.empty();
    }

    private SkillDirectoryScanner.ScanResult scanRoot(Path root, Instant now) {
        CachedCatalog cached = catalogs.get(root);
        if (cached != null && !cached.expired(now, ttl) && cached.mtime().equals(mtimeOf(root))) {
            return cached.result();
        }
        SkillDirectoryScanner.ScanResult result = scanner.scan(root, root.toString());
        catalogs.put(root, new CachedCatalog(result, mtimeOf(root), now));
        return result;
    }

    private static Instant mtimeOf(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (IOException error) {
            return Instant.EPOCH;
        }
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException error) {
            return -1L;
        }
    }

    /**
     * 目录快照缓存项。
     *
     * @param result 扫描结果
     * @param mtime 根目录 mtime
     * @param scannedAt 扫描时间
     */
    private record CachedCatalog(SkillDirectoryScanner.ScanResult result, Instant mtime,
            Instant scannedAt) {

        boolean expired(Instant now, Duration ttl) {
            return now.isAfter(scannedAt.plus(ttl));
        }
    }

    /**
     * 正文缓存项。
     *
     * @param mtime 文件 mtime
     * @param size 文件大小
     * @param body 去 frontmatter 的正文
     */
    private record CachedContent(Instant mtime, long size, String body) {
    }
}
