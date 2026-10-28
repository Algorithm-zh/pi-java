package com.piagent.infrastructure.tool.index;

import com.piagent.infrastructure.tool.FileContentClassifier;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 索引构建：读取文本、统计词频、提取符号。
 *
 * <p>所有上限都是为了本地仓库不失控：单文件 1 MB、总量 32 MB、每文件最多
 * 2000 个不同词。超过上限的文件被跳过并在结果中报告，而不是静默截断内容。</p>
 */
final class CodeIndexBuilder {

    static final int MAX_FILE_BYTES = 1024 * 1024;
    static final int MAX_TOTAL_BYTES = 32 * 1024 * 1024;
    static final int MAX_CHARS = 300_000;
    static final int MAX_TERMS_PER_FILE = 2_000;

    private CodeIndexBuilder() {
    }

    /**
     * 构建快照。
     *
     * @param root 索引根目录
     * @param stamps 待索引文件指纹
     * @param walkTruncated 遍历是否已截断
     * @return 快照
     */
    static IndexSnapshot build(Path root, List<IndexSnapshot.Stamp> stamps,
            boolean walkTruncated) {
        Map<String, IndexSnapshot.IndexedFile> files = new LinkedHashMap<>();
        Map<String, Integer> docFreq = new HashMap<>();
        Counters counters = new Counters();
        for (IndexSnapshot.Stamp stamp : stamps) {
            if (counters.truncated) {
                break;
            }
            IndexSnapshot.IndexedFile file = indexFile(root, stamp, counters);
            if (file == null) {
                continue;
            }
            files.put(file.relativePath(), file);
            counters.bytes += stamp.size();
            addDocFreq(docFreq, file);
        }
        return new IndexSnapshot(Map.copyOf(files), Map.copyOf(docFreq),
                counters.binary, counters.large, walkTruncated || counters.truncated,
                counters.bytes);
    }

    private static IndexSnapshot.IndexedFile indexFile(Path root,
            IndexSnapshot.Stamp stamp, Counters counters) {
        if (stamp.size() > MAX_FILE_BYTES) {
            counters.large++;
            return null;
        }
        if (counters.bytes + stamp.size() > MAX_TOTAL_BYTES) {
            counters.truncated = true;
            return null;
        }
        Path file = root.resolve(stamp.relativePath());
        try {
            if (FileContentClassifier.isBinary(file)) {
                counters.binary++;
                return null;
            }
            String content = read(file);
            Map<String, Integer> terms = CodeTokenizer.counts(content, MAX_TERMS_PER_FILE);
            List<SourceSymbol> symbols = SourceSymbols.extract(content, stamp.relativePath());
            int tokenCount = terms.values().stream().mapToInt(Integer::intValue).sum();
            return new IndexSnapshot.IndexedFile(stamp.relativePath(), stamp.size(), terms,
                    CodeTokenizer.pathTokens(stamp.relativePath()), symbolTerms(symbols),
                    symbols, tokenCount);
        } catch (IOException | RuntimeException error) {
            return null;
        }
    }

    private static String read(Path file) throws IOException {
        String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        return content.length() > MAX_CHARS ? content.substring(0, MAX_CHARS) : content;
    }

    private static Set<String> symbolTerms(List<SourceSymbol> symbols) {
        Set<String> terms = new HashSet<>();
        for (SourceSymbol symbol : symbols) {
            terms.addAll(CodeTokenizer.tokenize(symbol.name()));
        }
        return Set.copyOf(terms);
    }

    private static void addDocFreq(Map<String, Integer> docFreq,
            IndexSnapshot.IndexedFile file) {
        Set<String> terms = new HashSet<>(file.terms().keySet());
        terms.addAll(file.pathTerms());
        terms.addAll(file.symbolTerms());
        for (String term : terms) {
            docFreq.merge(term, 1, Integer::sum);
        }
    }

    private static final class Counters {
        private long bytes;
        private int binary;
        private int large;
        private boolean truncated;
    }
}
