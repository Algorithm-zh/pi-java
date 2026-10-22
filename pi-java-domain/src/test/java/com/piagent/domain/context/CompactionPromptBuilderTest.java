package com.piagent.domain.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.piagent.domain.session.SessionFixtures;
import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.payload.MessagePayload;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 压缩请求组装与模型输出小节解析测试。
 */
class CompactionPromptBuilderTest {

    private static final SessionId SESSION = SessionFixtures.sessionId(1);

    private final CompactionPromptBuilder builder = new CompactionPromptBuilder();

    @Test
    void systemPromptListsAllSections() {
        String prompt = builder.systemPrompt();

        for (SummarySection section : SummarySection.values()) {
            assertThat(prompt).contains("## " + section.title());
        }
    }

    @Test
    void rendersEntriesInOrderAndCarriesPreviousSummary() {
        SessionEntry user = SessionFixtures.message(SESSION, 1, null, SessionFixtures.user("修复登录"));
        SessionEntry assistant = SessionFixtures.message(SESSION, 2, user.id(),
                SessionFixtures.assistantWithToolCall("call-1", "read"));

        String text = builder.userMessage(List.of(user, assistant), List.of(), "旧摘要：已完成 A");

        assertThat(text).contains("已有摘要", "旧摘要：已完成 A");
        assertThat(text.indexOf("[用户] 修复登录")).isLessThan(text.indexOf("[助手]"));
        assertThat(text).endsWith("现在输出摘要正文。");
    }

    @Test
    void marksSplitTurn() {
        SessionEntry user = SessionFixtures.message(SESSION, 1, null, SessionFixtures.user("长任务"));

        String text = builder.userMessage(List.of(user), List.of(user), null);

        assertThat(text).contains("被拆成两段");
        assertThat(text).doesNotContain("已有摘要");
    }

    @Test
    void skipsAuditEntries() {
        SessionEntry label = SessionFixtures.label(SESSION, 1, null, SessionFixtures.entryId(9));

        assertThat(builder.render(label)).isNull();
    }

    @Test
    void truncatesToolResults() {
        String huge = "y".repeat(CompactionPromptBuilder.MAX_TOOL_RESULT_CHARS + 100);
        SessionEntry result = SessionFixtures.entry(SESSION, 1, null,
                new MessagePayload(new com.piagent.domain.session.model.message.ToolResultMessage(
                        "call-1", "read",
                        List.of(new com.piagent.domain.session.model.message.TextBlock(huge)),
                        false, java.util.Map.of(), SessionFixtures.NOW)), 10);

        String rendered = builder.render(result);

        assertThat(rendered).contains("已截断");
        assertThat(rendered.length())
                .isLessThan(CompactionPromptBuilder.MAX_TOOL_RESULT_CHARS + 100);
    }

    @Test
    void keepsRecentHistoryWhenOverLimit() {
        List<SessionEntry> entries = java.util.stream.IntStream.range(0, 40)
                .mapToObj(index -> SessionFixtures.message(SESSION, index + 1,
                        index == 0 ? (EntryId) null : SessionFixtures.entryId(100 + index),
                        SessionFixtures.user("第" + index + "条 " + "z".repeat(2_000))))
                .toList();

        String text = builder.userMessage(entries, List.of(), null);

        assertThat(text).contains("更早的内容已省略").contains("第39条");
        assertThat(text).doesNotContain("第0条");
    }

    @Test
    void parsesSectionTitlesInSeveralFormats() {
        assertThat(CompactionPromptBuilder.parseSection("## 目标")).isEqualTo(SummarySection.GOAL);
        assertThat(CompactionPromptBuilder.parseSection("目标: 修复")).isEqualTo(SummarySection.GOAL);
        assertThat(CompactionPromptBuilder.parseSection("### Goal")).isEqualTo(SummarySection.GOAL);
        assertThat(CompactionPromptBuilder.parseSection("Next Steps")).isEqualTo(SummarySection.NEXT_STEPS);
        assertThat(CompactionPromptBuilder.parseSection("Summary")).isNull();
        assertThat(CompactionPromptBuilder.parseSection(null)).isNull();
    }
}
