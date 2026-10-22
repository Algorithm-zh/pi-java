package com.piagent.domain.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 压缩摘要格式化与收敛判定测试。
 *
 * <p>覆盖：小节顺序稳定、空小节不输出、文件清单、超限裁剪优先级、收敛三门槛。</p>
 */
class CompactionSummaryTest {

    private final CompactionSummaryFormatter formatter = new CompactionSummaryFormatter();

    @Test
    void rendersSectionsInFixedOrder() {
        SummaryDraft draft = new SummaryDraft(Map.of(
                SummarySection.NEXT_STEPS, "继续实现压缩用例",
                SummarySection.GOAL, "完成 M7"), List.of("README.md"), List.of());

        String text = formatter.format(draft);

        assertThat(text).startsWith("## 目标\n完成 M7")
                .contains("## 下一步\n继续实现压缩用例")
                .contains("## 关键上下文\n读取文件：README.md");
    }

    @Test
    void skipsEmptySections() {
        String text = formatter.format(new SummaryDraft(
                Map.of(SummarySection.GOAL, "目标内容"), List.of(), List.of()));

        assertThat(text).isEqualTo("## 目标\n目标内容");
    }

    @Test
    void rendersPlaceholderForEmptyDraft() {
        assertThat(formatter.format(SummaryDraft.empty()))
                .isEqualTo("(本段历史没有需要保留的信息)");
    }

    @Test
    void listsReadAndModifiedFiles() {
        String text = formatter.format(new SummaryDraft(Map.of(),
                List.of("a.md", "b.md"), List.of("c.txt")));

        assertThat(text).contains("读取文件：a.md, b.md").contains("修改文件：c.txt");
    }

    @Test
    void dropsProgressBeforeGoalWhenOverLimit() {
        String huge = "x".repeat(CompactionSummaryFormatter.MAX_CHARS);
        SummaryDraft draft = new SummaryDraft(Map.of(
                SummarySection.GOAL, "目标必须保留",
                SummarySection.PROGRESS, huge,
                SummarySection.NEXT_STEPS, "下一步必须保留"), List.of(), List.of());

        String text = formatter.format(draft);

        assertThat(text).contains("目标必须保留").contains("下一步必须保留");
        assertThat(text).doesNotContain("## 进展");
        assertThat(text.length()).isLessThanOrEqualTo(CompactionSummaryFormatter.MAX_CHARS + 20);
    }

    @Test
    void draftSectionLookupIsNullSafe() {
        SummaryDraft draft = SummaryDraft.empty();

        assertThat(draft.section(SummarySection.GOAL)).isEmpty();
        assertThat(draft.isEmpty()).isTrue();
        assertThat(draft.withSection(SummarySection.GOAL, "g").section(SummarySection.GOAL))
                .isEqualTo("g");
    }

    @Test
    void outcomeRequiresShrinkingBelowBudgetAndTarget() {
        ContextBudget budget = new ContextBudget(100, 10, 10);

        assertThat(new CompactionOutcome(60, 120, 120, 20).converged(budget)).isTrue();
        assertThat(new CompactionOutcome(95, 120, 120, 20).converged(budget))
                .as("超过可用额度 90").isFalse();
        assertThat(new CompactionOutcome(80, 100, 100, 20).converged(budget))
                .as("超过 70% 目标").isFalse();
        assertThat(new CompactionOutcome(80, 120, 10, 20).converged(budget))
                .as("摘要比原文还长").isFalse();
    }

    @Test
    void outcomeReportsSavedTokens() {
        assertThat(new CompactionOutcome(80, 120, 120, 20).savedTokens()).isEqualTo(40);
        assertThat(new CompactionOutcome(130, 120, 120, 20).savedTokens()).isZero();
    }
}
