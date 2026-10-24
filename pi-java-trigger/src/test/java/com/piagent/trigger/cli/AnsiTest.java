package com.piagent.trigger.cli;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AnsiTest {

    @Test
    void stylesArePlainWhenDisabled() {
        Ansi ansi = new Ansi(false);
        assertThat(ansi.red("错误")).isEqualTo("错误");
        assertThat(ansi.bold("")).isEmpty();
    }

    @Test
    void stylesWrapTextWhenEnabled() {
        Ansi ansi = new Ansi(true);
        assertThat(ansi.red("错误")).isEqualTo("\u001B[31m错误\u001B[0m");
    }

    @Test
    void widthCountsCjkAsTwoAndSkipsAnsi() {
        assertThat(Ansi.width("abc")).isEqualTo(3);
        assertThat(Ansi.width("你好")).isEqualTo(4);
        assertThat(Ansi.width("\u001B[31m你好\u001B[0m")).isEqualTo(4);
    }

    @Test
    void truncateKeepsWholeCodePoints() {
        assertThat(Ansi.truncate("abcdef", 4)).isEqualTo("abcd…");
        assertThat(Ansi.truncate("你好世界", 5)).isEqualTo("你好…");
        assertThat(Ansi.truncate("abc", 5)).isEqualTo("abc");
        assertThat(Ansi.truncate(null, 5)).isEmpty();
    }
}
