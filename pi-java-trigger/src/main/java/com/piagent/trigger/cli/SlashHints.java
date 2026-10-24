package com.piagent.trigger.cli;

import java.util.List;

/**
 * 斜杠命令的实时提示文本。
 *
 * <p>只生成提示字符串，不读写终端；高亮器把它以淡色追加在光标之后，
 * 用户输入 {@code /} 或命令前缀时就能看到候选，而不是先按 Tab。</p>
 */
final class SlashHints {

    private static final String NO_MATCH = "  回车按普通消息发送";

    private SlashHints() {
    }

    /**
     * 计算提示文本。
     *
     * @param buffer 当前输入缓冲区
     * @param maxWidth 提示可用的显示宽度
     * @return 提示；空字符串表示不显示
     */
    static String hint(String buffer, int maxWidth) {
        if (buffer == null || buffer.isEmpty() || buffer.charAt(0) != '/'
                || buffer.chars().anyMatch(Character::isWhitespace) || maxWidth <= 0) {
            return "";
        }
        List<String> matches = SlashCompleter.COMMANDS.stream()
                .filter(command -> command.startsWith(buffer))
                .toList();
        if (matches.isEmpty()) {
            return NO_MATCH;
        }
        if (matches.size() == 1) {
            String only = matches.get(0);
            if (only.length() == buffer.length()) {
                return "";
            }
            String suffix = only.substring(buffer.length()) + "  Tab";
            return suffix.length() <= maxWidth ? suffix : "";
        }
        return list(matches, maxWidth);
    }

    private static String list(List<String> matches, int maxWidth) {
        StringBuilder hint = new StringBuilder("  ");
        int shown = 0;
        for (String match : matches) {
            String piece = (shown == 0 ? "" : " ") + match;
            int reserve = shown + 1 < matches.size() ? 6 : 0;
            if (hint.length() + piece.length() + reserve > maxWidth && shown > 0) {
                break;
            }
            hint.append(piece);
            shown++;
        }
        if (shown < matches.size()) {
            hint.append(" …");
        }
        hint.append("  Tab");
        return shown == 0 || hint.length() > maxWidth ? "" : hint.toString();
    }
}
