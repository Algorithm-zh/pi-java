package com.piagent.domain.session.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 会话标题策略测试：占位识别、文本规整与截断。
 */
class SessionTitlesTest {

    @Test
    void treatsDefaultTitlesAsPlaceholders() {
        assertThat(SessionTitles.isPlaceholder("新会话")).isTrue();
        assertThat(SessionTitles.isPlaceholder("CLI 会话")).isTrue();
        assertThat(SessionTitles.isPlaceholder("New session")).isTrue();
        assertThat(SessionTitles.isPlaceholder("  ")).isTrue();
        assertThat(SessionTitles.isPlaceholder(null)).isTrue();
    }

    @Test
    void keepsManualTitles() {
        assertThat(SessionTitles.isPlaceholder("排查启动失败")).isFalse();
        assertThat(SessionTitles.isPlaceholder("重构会话标题")).isFalse();
    }

    @Test
    void derivesTitleFromFirstContentLine() {
        String title = SessionTitles.fromText("\n\n## 帮我排查启动失败\n\n日志里一直报错");

        assertThat(title).isEqualTo("帮我排查启动失败");
    }

    @Test
    void collapsesWhitespaceAndListMarkers() {
        assertThat(SessionTitles.fromText("-   把   这个   文件   重构一下"))
                .isEqualTo("把 这个 文件 重构一下");
        assertThat(SessionTitles.fromText("“整理一下 README”")).isEqualTo("整理一下 README");
    }

    @Test
    void keepsCommandTitlesReadable() {
        assertThat(SessionTitles.fromText(
                "/tool com.piagent.example.timer.countdown {\"seconds\":2}"))
                .isEqualTo("调用工具 · countdown");
        assertThat(SessionTitles.fromText("/write docs/readme.md 把标题改一下"))
                .isEqualTo("/write docs/readme.md");
    }

    @Test
    void truncatesLongMessagesByCodePoint() {
        String title = SessionTitles.fromText("请帮我把这个项目里所有过时的文档都更新一遍并且检查链接");

        assertThat(title.codePointCount(0, title.length())).isEqualTo(SessionTitles.MAX_LENGTH);
        assertThat(title).endsWith("…");
    }

    @Test
    void doesNotSplitSurrogatePairsWhenTruncating() {
        String emoji = "🙂".repeat(SessionTitles.MAX_LENGTH + 4);
        String title = SessionTitles.fromText(emoji);

        assertThat(title).endsWith("…");
        assertThat(title.substring(0, title.length() - 1))
                .isEqualTo("🙂".repeat(SessionTitles.MAX_LENGTH - 1));
    }

    @Test
    void returnsEmptyTitleForBlankText() {
        assertThat(SessionTitles.fromText("   \n\t ")).isEmpty();
        assertThat(SessionTitles.fromText(null)).isEmpty();
    }
}
