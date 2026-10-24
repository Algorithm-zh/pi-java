package com.piagent.trigger.cli;

/**
 * TUI 的 ANSI 样式与显示宽度工具。
 *
 * <p>样式关闭时所有方法原样返回，便于单元测试和 {@code NO_COLOR} 环境获得纯文本。
 * 宽度把东亚宽字符按 2 计算，避免中文路径把工具摘要撑出屏幕。</p>
 */
final class Ansi {

    private static final String RESET = "\u001B[0m";

    private final boolean enabled;

    Ansi(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 是否允许输出颜色；设置 {@code NO_COLOR} 环境变量时关闭。
     *
     * @return true 表示可以使用 ANSI 颜色
     */
    static boolean colorCapable() {
        return System.getenv("NO_COLOR") == null;
    }

    /**
     * 加粗。
     *
     * @param text 文本
     * @return 带样式的文本
     */
    String bold(String text) {
        return style("\u001B[1m", text);
    }

    /**
     * 变暗。
     *
     * @param text 文本
     * @return 带样式的文本
     */
    String dim(String text) {
        return style("\u001B[2m", text);
    }

    /**
     * 青色。
     *
     * @param text 文本
     * @return 带样式的文本
     */
    String cyan(String text) {
        return style("\u001B[36m", text);
    }

    /**
     * 绿色。
     *
     * @param text 文本
     * @return 带样式的文本
     */
    String green(String text) {
        return style("\u001B[32m", text);
    }

    /**
     * 黄色。
     *
     * @param text 文本
     * @return 带样式的文本
     */
    String yellow(String text) {
        return style("\u001B[33m", text);
    }

    /**
     * 红色。
     *
     * @param text 文本
     * @return 带样式的文本
     */
    String red(String text) {
        return style("\u001B[31m", text);
    }

    /**
     * 品牌绿：用于提示符、助手正文标记与成功状态。
     *
     * @param text 文本
     * @return 带样式的文本
     */
    String accent(String text) {
        return style("\u001B[38;5;78m", text);
    }

    /**
     * 暖琥珀：用于工具调用与压缩过程，和正文区分。
     *
     * @param text 文本
     * @return 带样式的文本
     */
    String amber(String text) {
        return style("\u001B[38;5;179m", text);
    }

    /**
     * 计算可见宽度，跳过 ANSI 转义序列。
     *
     * @param text 文本
     * @return 终端列数
     */
    static int width(String text) {
        int total = 0;
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
            index += Character.charCount(codePoint);
            if (codePoint == 0x1B) {
                int end = text.indexOf('m', index);
                if (end < 0) {
                    break;
                }
                index = end + 1;
                continue;
            }
            total += codePointWidth(codePoint);
        }
        return total;
    }

    /**
     * 按显示宽度截断纯文本。
     *
     * @param text 纯文本
     * @param maxWidth 最大列数
     * @return 截断后的文本，发生截断时追加省略号
     */
    static String truncate(String text, int maxWidth) {
        if (text == null || maxWidth <= 0) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        int used = 0;
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
            int codeWidth = codePointWidth(codePoint);
            if (used + codeWidth > maxWidth) {
                return result.append('…').toString();
            }
            result.appendCodePoint(codePoint);
            used += codeWidth;
            index += Character.charCount(codePoint);
        }
        return result.toString();
    }

    private String style(String prefix, String text) {
        return enabled && !text.isEmpty() ? prefix + text + RESET : text;
    }

    private static int codePointWidth(int codePoint) {
        if (Character.isISOControl(codePoint)) {
            return 0;
        }
        if (codePoint >= 0x1100 && (codePoint <= 0x115F
                || (codePoint >= 0x2E80 && codePoint <= 0xA4CF)
                || (codePoint >= 0xAC00 && codePoint <= 0xD7A3)
                || (codePoint >= 0xF900 && codePoint <= 0xFAFF)
                || (codePoint >= 0xFE30 && codePoint <= 0xFE4F)
                || (codePoint >= 0xFF00 && codePoint <= 0xFF60)
                || (codePoint >= 0xFFE0 && codePoint <= 0xFFE6)
                || (codePoint >= 0x1F300 && codePoint <= 0x1FAFF)
                || (codePoint >= 0x20000 && codePoint <= 0x3FFFD))) {
            return 2;
        }
        return 1;
    }
}
