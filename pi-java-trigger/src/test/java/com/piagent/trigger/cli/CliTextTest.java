package com.piagent.trigger.cli;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CliTextTest {

    @Test
    void oneLineFlattensWhitespace() {
        assertThat(CliText.oneLine("  第一行\n\n第二行\t结束  ", 100))
                .isEqualTo("第一行 第二行 结束");
    }

    @Test
    void oneLineTruncatesWithEllipsis() {
        assertThat(CliText.oneLine("abcdef", 3)).isEqualTo("abc…");
    }

    @Test
    void truncateKeepsNewlines() {
        assertThat(CliText.truncate("a\nbcd", 3)).isEqualTo("a\nb…");
    }

    @Test
    void nullIsEmpty() {
        assertThat(CliText.oneLine(null, 10)).isEmpty();
        assertThat(CliText.truncate(null, 10)).isEmpty();
    }
}
