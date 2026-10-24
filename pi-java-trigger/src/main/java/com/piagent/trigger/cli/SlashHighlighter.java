package com.piagent.trigger.cli;

import java.util.regex.Pattern;
import org.jline.reader.Highlighter;
import org.jline.reader.LineReader;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

/**
 * 把斜杠命令提示以淡色追加在输入之后。
 *
 * <p>提示只是显示层，不改变缓冲区内容：光标停在用户输入末尾，
 * 后面的淡色文字由 {@link SlashHints} 计算，回车发送的仍是用户输入。</p>
 */
final class SlashHighlighter implements Highlighter {

    private static final int MIN_HINT_WIDTH = 12;

    @Override
    public AttributedString highlight(LineReader reader, String buffer) {
        AttributedStringBuilder builder = new AttributedStringBuilder();
        builder.append(buffer);
        int width = reader.getTerminal().getWidth() - buffer.length() - 6;
        String hint = SlashHints.hint(buffer, Math.max(width, MIN_HINT_WIDTH));
        if (hint.isEmpty()) {
            return builder.toAttributedString();
        }
        if (Ansi.colorCapable()) {
            builder.append(hint, AttributedStyle.DEFAULT.faint());
        } else {
            builder.append(hint);
        }
        return builder.toAttributedString();
    }

    @Override
    public void setErrorPattern(Pattern errorPattern) {
        // 不使用 JLine 的错误高亮
    }

    @Override
    public void setErrorIndex(int errorIndex) {
        // 不使用 JLine 的错误高亮
    }
}
