package com.piagent.trigger.cli;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 斜杠命令实时提示的文本规则。
 */
class SlashHintsTest {

    @Test
    void hidesHintForPlainTextAndArguments() {
        assertThat(SlashHints.hint(null, 80)).isEmpty();
        assertThat(SlashHints.hint("", 80)).isEmpty();
        assertThat(SlashHints.hint("你好", 80)).isEmpty();
        assertThat(SlashHints.hint("/write notes.txt 你好", 80)).isEmpty();
    }

    @Test
    void listsAllCommandsForBareSlashWhenWidthAllows() {
        String hint = SlashHints.hint("/", 100);
        assertThat(hint).contains("/help", "/model", "/session", "/skill:", "/tree")
                .endsWith("Tab");
    }

    @Test
    void truncatesCommandListOnNarrowTerminals() {
        String hint = SlashHints.hint("/", 24);
        assertThat(hint).contains("…").contains("Tab").doesNotContain("/exit");
        assertThat(hint.length()).isLessThanOrEqualTo(24);
    }

    @Test
    void completesSingleMatchInPlace() {
        assertThat(SlashHints.hint("/he", 80)).isEqualTo("lp  Tab");
        assertThat(SlashHints.hint("/m", 80)).isEqualTo("odel  Tab");
    }

    @Test
    void hidesHintForExactCommand() {
        assertThat(SlashHints.hint("/help", 80)).isEmpty();
        assertThat(SlashHints.hint("/skill:", 80)).isEmpty();
    }

    @Test
    void tellsUserUnknownSlashTextIsSentAsMessage() {
        assertThat(SlashHints.hint("/x", 80)).contains("普通消息");
    }
}
