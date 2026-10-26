package com.piagent.domain.session.service;

import java.util.Locale;
import java.util.Set;

/**
 * 会话标题策略：识别占位标题，并从首条用户消息生成可读的短标题。
 *
 * <p>不调用模型，只做本地规整：取首个非空行、去掉 Markdown 前缀与命令的 JSON
 * 参数、折叠空白，再按码点截断。这样首条消息发出后标题立即可用，也不依赖
 * 模型档案是否配置正确。</p>
 */
public final class SessionTitles {

    /** 新建会话使用的占位标题。 */
    public static final String DEFAULT_TITLE = "新会话";

    /** 自动生成标题的最大字符数，按 Unicode 码点计。 */
    public static final int MAX_LENGTH = 24;

    private static final Set<String> PLACEHOLDERS = Set.of(
            "新会话", "会话", "新对话", "cli 会话", "未命名会话", "new session", "untitled");

    private SessionTitles() {
    }

    /**
     * 判断标题是否为可以自动覆盖的占位值。
     *
     * <p>用户手动填写的标题不会命中这里，因此首条消息只覆盖默认标题。</p>
     *
     * @param title 标题
     * @return 是否为空或占位标题
     */
    public static boolean isPlaceholder(String title) {
        return title == null || title.isBlank()
                || PLACEHOLDERS.contains(title.strip().toLowerCase(Locale.ROOT));
    }

    /**
     * 从用户文本生成短标题。
     *
     * @param text 首条用户消息
     * @return 短标题；没有可读内容时返回空串
     */
    public static String fromText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String line = firstContentLine(text);
        if (line.startsWith("/")) {
            line = commandTitle(line);
        }
        line = collapse(line);
        line = stripDecoration(line);
        line = trimQuotes(line);
        return truncate(line);
    }

    private static String firstContentLine(String text) {
        for (String line : text.split("\\R")) {
            if (!line.isBlank()) {
                return line.strip();
            }
        }
        return "";
    }

    /**
     * 命令消息只保留命令和第一个参数：参数通常是 JSON 或长内容，放进标题只会更难读。
     */
    private static String commandTitle(String line) {
        int space = line.indexOf(' ');
        if (space < 0) {
            return line;
        }
        String command = line.substring(0, space);
        String argument = line.substring(space + 1).strip();
        int argumentEnd = argument.indexOf(' ');
        if (argumentEnd > 0) {
            argument = argument.substring(0, argumentEnd);
        }
        String shortName = command.equalsIgnoreCase("/tool") ? shorten(argument) : argument;
        if (shortName.isEmpty()) {
            return command;
        }
        return command.equalsIgnoreCase("/tool")
                ? "调用工具 · " + shortName
                : command + " " + shortName;
    }

    /**
     * 工具名带命名空间前缀时只保留末段，例如 com.piagent.example.timer.countdown。
     */
    private static String shorten(String argument) {
        int dot = argument.lastIndexOf('.');
        if (dot < 0 || dot == argument.length() - 1) {
            return argument;
        }
        return argument.substring(dot + 1);
    }

    private static String collapse(String value) {
        return value.replaceAll("\\s+", " ").strip();
    }

    private static String stripDecoration(String value) {
        String result = value;
        while (!result.isEmpty() && "#>*-+`~".indexOf(result.charAt(0)) >= 0) {
            result = result.substring(1).strip();
        }
        return result;
    }

    private static String trimQuotes(String value) {
        if (value.length() < 2) {
            return value;
        }
        char first = value.charAt(0);
        char last = value.charAt(value.length() - 1);
        boolean paired = (first == last && (first == '"' || first == '\''))
                || (first == '“' && last == '”')
                || (first == '「' && last == '」');
        return paired ? value.substring(1, value.length() - 1).strip() : value;
    }

    private static String truncate(String value) {
        if (value.codePointCount(0, value.length()) <= MAX_LENGTH) {
            return value;
        }
        int end = value.offsetByCodePoints(0, MAX_LENGTH - 1);
        return value.substring(0, end).stripTrailing() + "…";
    }
}
