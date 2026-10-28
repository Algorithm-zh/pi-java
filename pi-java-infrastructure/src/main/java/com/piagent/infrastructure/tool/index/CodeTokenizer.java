package com.piagent.infrastructure.tool.index;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 代码与文档的轻量分词器。
 *
 * <p>面向检索而非常规自然语言处理：ASCII 标识符按下划线与驼峰拆开，同时保留
 * 原词；中文按二元组切分，避免引入额外分词依赖。停用词只去掉英文虚词与少量
 * 中文疑问词，不删除领域词汇。</p>
 */
final class CodeTokenizer {

    private static final int MIN_ASCII_LENGTH = 2;
    private static final int MAX_TOKEN_LENGTH = 64;
    private static final Pattern CAMEL = Pattern.compile("[A-Z]+(?![a-z])|[A-Z]?[a-z]+|[0-9]+");
    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "for", "with", "from", "that", "this", "are", "was", "were",
            "has", "have", "had", "not", "but", "you", "your", "our", "their", "its",
            "into", "about", "over", "under", "than", "then", "when", "where", "which",
            "who", "whom", "what", "how", "why", "can", "could", "should", "would",
            "will", "may", "might", "must", "does", "did", "doing", "use", "using",
            "used", "get", "gets", "set", "sets", "add", "adds", "new", "all", "any",
            "each", "some", "more", "most", "other", "only", "also", "just", "please",
            "find", "show", "如何", "什么", "哪里", "怎么", "这个", "那个", "一个",
            "可以", "需要", "我们", "你们", "他们", "以及", "但是", "因为", "所以",
            "如果", "然后", "就是", "不是", "没有", "已经", "进行",
            "的", "了", "是", "在", "和", "与", "及");

    private CodeTokenizer() {
    }

    /**
     * 把文本切成去重后的词序列。
     *
     * @param text 原文
     * @return 词序列
     */
    static List<String> tokenize(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        forEachToken(text, tokens::add);
        return List.copyOf(tokens);
    }

    /**
     * 统计词频。
     *
     * @param text 原文
     * @param maxTerms 最多保留的不同词数，防止超大文件撑爆内存
     * @return 词频表
     */
    static Map<String, Integer> counts(String text, int maxTerms) {
        Map<String, Integer> counts = new HashMap<>();
        forEachToken(text, token -> {
            Integer previous = counts.get(token);
            if (previous != null) {
                counts.put(token, previous + 1);
            } else if (counts.size() < maxTerms) {
                counts.put(token, 1);
            }
        });
        return counts;
    }

    /**
     * 提取路径中的词，目录名与扩展名都会参与检索。
     *
     * @param relativePath 使用 {@code /} 的相对路径
     * @return 词集合
     */
    static Set<String> pathTokens(String relativePath) {
        Set<String> tokens = new LinkedHashSet<>();
        forEachToken(relativePath, tokens::add);
        return Set.copyOf(tokens);
    }

    private static void forEachToken(String text, Consumer<String> consumer) {
        if (text == null || text.isEmpty()) {
            return;
        }
        StringBuilder ascii = new StringBuilder();
        StringBuilder cjk = new StringBuilder();
        for (int index = 0; index < text.length();) {
            int codePoint = text.codePointAt(index);
            index += Character.charCount(codePoint);
            if (isCjk(codePoint)) {
                flushAscii(ascii, consumer);
                cjk.appendCodePoint(codePoint);
            } else if (Character.isLetterOrDigit(codePoint) || codePoint == '_') {
                flushCjk(cjk, consumer);
                ascii.appendCodePoint(codePoint);
            } else {
                flushAscii(ascii, consumer);
                flushCjk(cjk, consumer);
            }
        }
        flushAscii(ascii, consumer);
        flushCjk(cjk, consumer);
    }

    private static void flushAscii(StringBuilder buffer, Consumer<String> consumer) {
        if (buffer.isEmpty()) {
            return;
        }
        for (String part : buffer.toString().split("_")) {
            addWord(part, consumer);
        }
        buffer.setLength(0);
    }

    private static void addWord(String raw, Consumer<String> consumer) {
        String word = raw.strip();
        if (word.isEmpty()) {
            return;
        }
        Set<String> pieces = new LinkedHashSet<>();
        pieces.add(word.toLowerCase(Locale.ROOT));
        Matcher matcher = CAMEL.matcher(word);
        while (matcher.find()) {
            pieces.add(matcher.group().toLowerCase(Locale.ROOT));
        }
        pieces.forEach(piece -> addAsciiToken(piece, consumer));
    }

    private static void addAsciiToken(String token, Consumer<String> consumer) {
        if (token.length() < MIN_ASCII_LENGTH || token.length() > MAX_TOKEN_LENGTH) {
            return;
        }
        if (!STOP_WORDS.contains(token)) {
            consumer.accept(token);
        }
    }

    private static void flushCjk(StringBuilder buffer, Consumer<String> consumer) {
        if (buffer.isEmpty()) {
            return;
        }
        int[] points = buffer.toString().codePoints().toArray();
        if (points.length == 1) {
            addCjkToken(new String(points, 0, 1), consumer);
        } else {
            for (int index = 0; index + 1 < points.length; index++) {
                addCjkToken(new String(points, index, 2), consumer);
            }
            if (points.length <= 4) {
                addCjkToken(new String(points, 0, points.length), consumer);
            }
        }
        buffer.setLength(0);
    }

    private static void addCjkToken(String token, Consumer<String> consumer) {
        if (token.length() <= MAX_TOKEN_LENGTH && !STOP_WORDS.contains(token)) {
            consumer.accept(token);
        }
    }

    private static boolean isCjk(int codePoint) {
        return (codePoint >= 0x3400 && codePoint <= 0x4DBF)
                || (codePoint >= 0x4E00 && codePoint <= 0x9FFF)
                || (codePoint >= 0xF900 && codePoint <= 0xFAFF)
                || (codePoint >= 0x3040 && codePoint <= 0x30FF);
    }
}
