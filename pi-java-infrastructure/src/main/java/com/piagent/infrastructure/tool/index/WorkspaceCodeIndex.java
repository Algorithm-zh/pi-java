package com.piagent.infrastructure.tool.index;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.infrastructure.tool.ToolText;
import com.piagent.infrastructure.tool.WorkspaceWalker;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工作区代码索引：扫描、缓存与检索。
 *
 * <p>每次调用先按文件大小与修改时间生成指纹；指纹不变就复用上一次的词频
 * 快照，变了才重建。缓存按工作区保留最近 8 份，避免多会话逐个占用内存。
 * 索引与检索都只使用 JDK，不执行工作区里的代码。</p>
 */
public final class WorkspaceCodeIndex {

    private static final int MAX_FILES = 5_000;
    private static final int MAX_DEPTH = 32;
    private static final int MAX_WORKSPACES = 8;

    private final Map<Path, Cached> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Path, Cached> eldest) {
            return size() > MAX_WORKSPACES;
        }
    };

    /**
     * 生成仓库概览，或按查询返回相关文件排名。
     *
     * @param indexRoot 索引根目录
     * @param relativePrefix 范围前缀，空串表示整个索引根
     * @param singleFile 是否只匹配单个文件
     * @param query 查询词；空白时返回概览
     * @param maxFiles 最多返回的文件数
     * @return 结果
     */
    public RepoMap search(Path indexRoot, String relativePrefix, boolean singleFile,
            String query, int maxFiles) {
        Path root = indexRoot.toAbsolutePath().normalize();
        IndexSnapshot snapshot = snapshot(root);
        IndexScope scope = new IndexScope(normalizePrefix(relativePrefix), singleFile);
        List<String> tokens = CodeTokenizer.tokenize(query);
        if (tokens.isEmpty()) {
            String text = RepoMapOverview.render(snapshot, scope);
            return new RepoMap(text, snapshot.files().size(), snapshot.files().size(),
                    snapshot.truncated());
        }
        List<FileHit> hits = rank(snapshot, scope, tokens);
        int limit = Math.min(Math.max(1, maxFiles), hits.size());
        List<FileHit> top = hits.subList(0, limit);
        String text = RepoMapFormatter.search(root, scope, query, top, hits.size(), limit);
        return new RepoMap(text, hits.size(), snapshot.files().size(), snapshot.truncated());
    }

    private IndexSnapshot snapshot(Path root) {
        Scan scan = scan(root);
        synchronized (cache) {
            Cached cached = cache.get(root);
            if (cached != null && cached.scan().equals(scan)) {
                return cached.snapshot();
            }
        }
        IndexSnapshot built = CodeIndexBuilder.build(root, scan.stamps(), scan.truncated());
        synchronized (cache) {
            cache.put(root, new Cached(scan, built));
        }
        return built;
    }

    private static Scan scan(Path root) {
        try {
            WorkspaceWalker.WalkResult walk =
                    WorkspaceWalker.walk(root, MAX_DEPTH, MAX_FILES, false);
            List<IndexSnapshot.Stamp> stamps = new ArrayList<>();
            for (Path file : walk.paths()) {
                try {
                    stamps.add(new IndexSnapshot.Stamp(ToolText.relative(root, file),
                            Files.size(file), Files.getLastModifiedTime(file).toMillis()));
                } catch (IOException ignored) {
                    // 单个文件读不到时跳过；下次调用会重新尝试。
                }
            }
            stamps.sort(Comparator.comparing(IndexSnapshot.Stamp::relativePath));
            return new Scan(stamps, walk.truncated());
        } catch (IOException error) {
            throw new DomainException(DomainErrorCode.TOOL_EXECUTION_FAILED,
                    "遍历工作区失败: " + root, error);
        }
    }

    private static List<FileHit> rank(IndexSnapshot snapshot, IndexScope scope,
            List<String> tokens) {
        Set<String> unique = new LinkedHashSet<>(tokens);
        int total = Math.max(1, snapshot.files().size());
        double average = averageLength(snapshot);
        List<FileHit> hits = new ArrayList<>();
        for (IndexSnapshot.IndexedFile file : snapshot.files().values()) {
            if (!scope.matches(file.relativePath())) {
                continue;
            }
            double score = score(file, unique, snapshot.docFreq(), total, average);
            if (score > 0) {
                hits.add(new FileHit(file, score));
            }
        }
        hits.sort(Comparator.comparingDouble(FileHit::score).reversed()
                .thenComparing(hit -> hit.file().relativePath()));
        return hits;
    }

    private static double averageLength(IndexSnapshot snapshot) {
        if (snapshot.files().isEmpty()) {
            return 1.0;
        }
        long total = 0;
        for (IndexSnapshot.IndexedFile file : snapshot.files().values()) {
            total += file.tokenCount();
        }
        return Math.max(1.0, (double) total / snapshot.files().size());
    }

    private static double score(IndexSnapshot.IndexedFile file, Set<String> tokens,
            Map<String, Integer> docFreq, int total, double average) {
        double score = 0;
        double k1 = 1.2;
        double b = 0.75;
        for (String token : tokens) {
            int frequency = docFreq.getOrDefault(token, 0);
            if (frequency == 0) {
                continue;
            }
            double idf = Math.log(1 + (total - frequency + 0.5) / (frequency + 0.5));
            int termFrequency = file.terms().getOrDefault(token, 0);
            if (termFrequency > 0) {
                double lengthNorm = 1 - b + b * file.tokenCount() / average;
                score += idf * termFrequency * (k1 + 1)
                        / (termFrequency + k1 * lengthNorm);
            }
            if (file.pathTerms().contains(token)) {
                score += idf * 2.5;
            }
            if (file.symbolTerms().contains(token)) {
                score += idf * 3.5;
            }
        }
        return score;
    }

    private static String normalizePrefix(String prefix) {
        if (prefix == null) {
            return "";
        }
        String value = prefix.replace('\\', '/').strip();
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return ".".equals(value) ? "" : value;
    }

    private record Scan(List<IndexSnapshot.Stamp> stamps, boolean truncated) {
    }

    private record Cached(Scan scan, IndexSnapshot snapshot) {
    }
}
