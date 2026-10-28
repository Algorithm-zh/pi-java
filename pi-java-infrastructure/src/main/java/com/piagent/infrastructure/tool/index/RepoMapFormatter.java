package com.piagent.infrastructure.tool.index;

import com.piagent.infrastructure.tool.ToolText;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 检索结果格式化：相关文件、符号与匹配行。
 *
 * <p>检索回答“改这个功能该看哪几个文件”。每个文件最多列四个符号、两行匹配，
 * 并明确提示继续用 read/grep 精读，避免把摘要当成完整内容。</p>
 */
final class RepoMapFormatter {

    private static final int MAX_SYMBOLS = 4;
    private static final int MAX_MATCHES = 2;
    private static final int MAX_MATCH_LENGTH = 180;

    private RepoMapFormatter() {
    }

    static String search(Path root, IndexScope scope, String query, List<FileHit> hits,
            int total, int shown) {
        StringBuilder out = new StringBuilder();
        out.append("查询「").append(query.strip()).append("」：命中 ").append(total)
                .append(" 个文件");
        if (!scope.prefix().isEmpty()) {
            out.append("（范围：").append(scope.prefix());
            if (!scope.singleFile()) {
                out.append('/');
            }
            out.append("）");
        }
        if (total > shown) {
            out.append("，显示前 ").append(shown).append(" 个");
        }
        out.append('\n');
        if (hits.isEmpty()) {
            out.append("没有找到相关文件。可以换用更短的关键词，或直接用 grep 搜索。\n");
            return out.toString();
        }
        List<String> tokens = CodeTokenizer.tokenize(query);
        int rank = 1;
        for (FileHit hit : hits) {
            IndexSnapshot.IndexedFile file = hit.file();
            out.append(rank++).append(". ").append(file.relativePath())
                    .append("（得分 ").append(String.format(Locale.ROOT, "%.1f", hit.score()))
                    .append("）\n");
            appendSymbols(out, file, tokens);
            appendMatches(out, root.resolve(file.relativePath()), tokens);
        }
        out.append("\n提示：用 read 打开相关文件精读；需要精确正则时用 grep。\n");
        return out.toString();
    }

    private static void appendSymbols(StringBuilder out, IndexSnapshot.IndexedFile file,
            List<String> tokens) {
        if (file.symbols().isEmpty()) {
            return;
        }
        Set<String> queryTokens = Set.copyOf(tokens);
        List<SourceSymbol> matched = file.symbols().stream()
                .filter(symbol -> intersects(symbol.name(), queryTokens))
                .limit(MAX_SYMBOLS)
                .toList();
        List<SourceSymbol> chosen = matched.isEmpty()
                ? file.symbols().stream().limit(MAX_SYMBOLS).toList()
                : matched;
        out.append("   结构：")
                .append(chosen.stream().map(symbol -> "L" + symbol.line() + " " + symbol.text())
                        .collect(Collectors.joining(" | ")))
                .append('\n');
    }

    private static void appendMatches(StringBuilder out, Path file, List<String> tokens) {
        try {
            if (Files.size(file) > CodeIndexBuilder.MAX_FILE_BYTES) {
                return;
            }
            String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            String[] lines = content.split("\\R", -1);
            int shown = 0;
            for (int index = 0; index < lines.length && shown < MAX_MATCHES; index++) {
                String line = lines[index].strip();
                if (line.isEmpty() || !containsAny(line, tokens)) {
                    continue;
                }
                out.append(shown == 0 ? "   匹配：" : "\n         ");
                out.append("L").append(index + 1).append(' ')
                        .append(ToolText.truncate(line, MAX_MATCH_LENGTH));
                shown++;
            }
            if (shown > 0) {
                out.append('\n');
            }
        } catch (IOException ignored) {
            // 文件在索引后消失时只是少一段摘录，不影响其余结果。
        }
    }

    private static boolean intersects(String name, Set<String> tokens) {
        return CodeTokenizer.tokenize(name).stream().anyMatch(tokens::contains);
    }

    private static boolean containsAny(String line, List<String> tokens) {
        String lower = line.toLowerCase(Locale.ROOT);
        return tokens.stream().anyMatch(lower::contains);
    }
}
