package com.piagent.infrastructure.tool.index;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量符号提取。
 *
 * <p>不引入语法树：按行匹配常见语言的类型、函数与方法声明。宁可少量漏报，
 * 也不要把 if/for 之类的语句当成符号；结果只用于仓库地图摘要，不参与编辑。</p>
 */
final class SourceSymbols {

    private static final int MAX_SYMBOLS = 60;
    private static final int MAX_TEXT_LENGTH = 120;
    private static final int MAX_LINE_LENGTH = 300;
    private static final Set<String> MARKDOWN_EXTENSIONS = Set.of("md", "markdown", "mdx");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private static final Set<String> STATEMENTS = Set.of(
            "if", "for", "while", "switch", "catch", "return", "new", "throw", "else",
            "do", "try", "finally", "assert", "yield", "await", "super", "this", "case");

    private static final Pattern TYPE = Pattern.compile(
            "^\\s*(?:(?:public|protected|private|static|final|abstract|sealed|non-sealed"
                    + "|strictfp|export|declare|default)\\s+)*"
                    + "(?:class|interface|enum|record|struct|trait)\\s+([A-Za-z_$][\\w$]*)");

    private static final Pattern METHOD = Pattern.compile(
            "^\\s*(?:@[\\w.$]+(?:\\([^)]*\\))?\\s*)*"
                    + "(?:(?:public|protected|private|static|final|abstract|synchronized"
                    + "|native|default|strictfp)\\s+)*"
                    + "[A-Za-z_$][\\w$<>\\[\\],.?]*\\s+([A-Za-z_$][\\w$]*)\\s*\\([^;{}]*\\)"
                    + "\\s*(?:throws\\s+[\\w$.,\\s]+)?[;{]?\\s*$");

    private static final Pattern CONSTRUCTOR = Pattern.compile(
            "^\\s*(?:(?:public|protected|private)\\s+)([A-Za-z_$][\\w$]*)\\s*\\([^;{}]*\\)"
                    + "\\s*(?:throws\\s+[\\w$.,\\s]+)?[;{]?\\s*$");

    private static final Pattern PYTHON = Pattern.compile(
            "^\\s*(?:async\\s+)?(?:def|class)\\s+([A-Za-z_]\\w*)");

    private static final Pattern JS_FUNCTION = Pattern.compile(
            "^\\s*(?:export\\s+)?(?:default\\s+)?(?:async\\s+)?function\\s+"
                    + "([A-Za-z_$][\\w$]*)");

    private static final Pattern JS_ARROW = Pattern.compile(
            "^\\s*(?:export\\s+)?(?:const|let|var)\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*"
                    + "(?:async\\s*)?(?:\\([^)]*\\)|[A-Za-z_$][\\w$]*)\\s*=>");

    private static final Pattern GO_FUNCTION = Pattern.compile(
            "^\\s*func\\s+(?:\\([^)]*\\)\\s*)?([A-Za-z_]\\w*)");

    private static final Pattern GO_TYPE = Pattern.compile(
            "^\\s*type\\s+([A-Za-z_]\\w*)\\s+(?:struct|interface)");

    private static final Pattern RUST = Pattern.compile(
            "^\\s*(?:pub(?:\\([^)]*\\))?\\s+)?(?:async\\s+)?"
                    + "(?:fn|struct|enum|trait)\\s+([A-Za-z_]\\w*)");

    private static final Pattern HEADING = Pattern.compile("^\\s{0,3}(#{1,3})\\s+(.+?)\\s*#*\\s*$");

    private static final Pattern SQL = Pattern.compile(
            "^\\s*CREATE\\s+(?:TABLE|VIEW|INDEX|FUNCTION|PROCEDURE|TRIGGER)\\s+([\\w.$]+)",
            Pattern.CASE_INSENSITIVE);

    private SourceSymbols() {
    }

    /**
     * 提取文件中的主要声明。
     *
     * <p>{@code #} 标题只在 Markdown 文件里识别，避免把 YAML/Python 注释当成结构。</p>
     *
     * @param content 文件内容
     * @param relativePath 相对路径，用于判断语言
     * @return 符号列表；同一行最多一个符号
     */
    static List<SourceSymbol> extract(String content, String relativePath) {
        if (content == null || content.isEmpty()) {
            return List.of();
        }
        boolean markdown = isMarkdown(relativePath);
        List<SourceSymbol> symbols = new ArrayList<>();
        Set<Integer> lines = new HashSet<>();
        String[] rows = content.split("\\R", -1);
        for (int index = 0; index < rows.length && symbols.size() < MAX_SYMBOLS; index++) {
            String line = rows[index].strip();
            if (line.isEmpty() || line.length() > MAX_LINE_LENGTH) {
                continue;
            }
            match(line, index + 1, symbols, lines, markdown);
        }
        return List.copyOf(symbols);
    }

    private static boolean isMarkdown(String relativePath) {
        if (relativePath == null) {
            return false;
        }
        String name = relativePath.toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot >= 0 && MARKDOWN_EXTENSIONS.contains(name.substring(dot + 1));
    }

    private static void match(String line, int number, List<SourceSymbol> symbols,
            Set<Integer> lines, boolean markdown) {
        if (markdown && addHeading(line, number, symbols, lines)) {
            return;
        }
        addMatched(TYPE, line, number, symbols, lines);
        addMethod(METHOD, line, number, symbols, lines);
        addMethod(CONSTRUCTOR, line, number, symbols, lines);
        addMatched(PYTHON, line, number, symbols, lines);
        addMatched(JS_FUNCTION, line, number, symbols, lines);
        addMatched(JS_ARROW, line, number, symbols, lines);
        addMatched(GO_FUNCTION, line, number, symbols, lines);
        addMatched(GO_TYPE, line, number, symbols, lines);
        addMatched(RUST, line, number, symbols, lines);
        addMatched(SQL, line, number, symbols, lines);
    }

    private static boolean addHeading(String line, int number, List<SourceSymbol> symbols,
            Set<Integer> lines) {
        Matcher matcher = HEADING.matcher(line);
        if (!matcher.matches()) {
            return false;
        }
        add(symbols, lines, number, matcher.group(2), line);
        return true;
    }

    private static void addMethod(Pattern pattern, String line, int number,
            List<SourceSymbol> symbols, Set<Integer> lines) {
        Matcher matcher = pattern.matcher(line);
        if (!matcher.find()) {
            return;
        }
        String name = matcher.group(1);
        if (STATEMENTS.contains(name) || STATEMENTS.contains(firstWord(line))
                || line.contains("=")) {
            return;
        }
        add(symbols, lines, number, name, line);
    }

    private static void addMatched(Pattern pattern, String line, int number,
            List<SourceSymbol> symbols, Set<Integer> lines) {
        Matcher matcher = pattern.matcher(line);
        if (matcher.find()) {
            add(symbols, lines, number, matcher.group(1), line);
        }
    }

    private static void add(List<SourceSymbol> symbols, Set<Integer> lines, int number,
            String name, String text) {
        if (symbols.size() >= MAX_SYMBOLS || lines.contains(number)
                || name == null || name.isBlank()) {
            return;
        }
        lines.add(number);
        symbols.add(new SourceSymbol(number, name, clean(text)));
    }

    private static String firstWord(String line) {
        String text = line.strip();
        int end = 0;
        while (end < text.length() && Character.isLetterOrDigit(text.charAt(end))) {
            end++;
        }
        return text.substring(0, end).toLowerCase(Locale.ROOT);
    }

    private static String clean(String text) {
        String value = WHITESPACE.matcher(text.strip()).replaceAll(" ");
        if (value.endsWith("{")) {
            value = value.substring(0, value.length() - 1).strip();
        }
        return value.length() <= MAX_TEXT_LENGTH
                ? value
                : value.substring(0, MAX_TEXT_LENGTH) + "…";
    }
}
