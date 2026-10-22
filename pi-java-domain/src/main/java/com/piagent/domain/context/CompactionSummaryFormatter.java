package com.piagent.domain.context;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * 把结构化摘要草稿渲染为最终写入 COMPACTION entry 的摘要文本。
 *
 * <p>输出格式固定为 Markdown 二级小节：</p>
 * <pre>
 * ## 目标
 * ...
 * ## 关键上下文
 * 读取文件：a.md, b.md
 * 修改文件：c.txt
 * </pre>
 *
 * <p>空小节不输出，避免产生“下一步：（无）”这类噪声；文件清单独占一节，
 * 便于模型与人工快速定位。整个输出有字符上限，超限时按“先丢进展、再丢决策”
 * 的顺序裁剪，保证目标与下一步始终保留。</p>
 */
public final class CompactionSummaryFormatter {

    /** 摘要正文最大字符数。 */
    public static final int MAX_CHARS = 8_000;

    private static final String READ_FILES_LABEL = "读取文件：";
    private static final String MODIFIED_FILES_LABEL = "修改文件：";
    private static final String TRUNCATED = "\n…（摘要已截断）";

    /**
     * 渲染摘要。
     *
     * @param draft 摘要草稿
     * @return Markdown 文本；草稿为空时返回占位说明
     */
    public String format(SummaryDraft draft) {
        Objects.requireNonNull(draft, "draft 不能为空");
        String full = render(draft);
        if (full.length() <= MAX_CHARS) {
            return full.isBlank() ? "(本段历史没有需要保留的信息)" : full;
        }
        return trimToFit(draft);
    }

    private String render(SummaryDraft draft) {
        StringBuilder text = new StringBuilder();
        for (SummarySection section : SummarySection.values()) {
            if (section == SummarySection.CRITICAL_CONTEXT) {
                continue;
            }
            appendSection(text, section, draft.section(section));
        }
        appendFiles(text, draft);
        return text.toString().strip();
    }

    private void appendFiles(StringBuilder text, SummaryDraft draft) {
        String body = filesBody(draft);
        if (!body.isBlank()) {
            appendSection(text, SummarySection.CRITICAL_CONTEXT, body);
        }
    }

    private String filesBody(SummaryDraft draft) {
        StringBuilder body = new StringBuilder();
        if (!draft.readFiles().isEmpty()) {
            body.append(READ_FILES_LABEL).append(String.join(", ", draft.readFiles())).append('\n');
        }
        if (!draft.modifiedFiles().isEmpty()) {
            body.append(MODIFIED_FILES_LABEL).append(String.join(", ", draft.modifiedFiles()));
        }
        return body.toString().strip();
    }

    private void appendSection(StringBuilder text, SummarySection section, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        if (!text.isEmpty()) {
            text.append('\n');
        }
        text.append("## ").append(section.title()).append('\n').append(content.strip()).append('\n');
    }

    /**
     * 超限裁剪：按“进展 → 决策 → 关键上下文 → 约束”的顺序逐级丢弃，
     * 目标是每一级丢弃后仍然超限才丢下一级；目标与下一步永不丢弃。
     */
    private String trimToFit(SummaryDraft draft) {
        Map<SummarySection, Boolean> dropped = new EnumMap<>(SummarySection.class);
        SummarySection[] droppable = {
            SummarySection.PROGRESS, SummarySection.DECISIONS,
            SummarySection.CRITICAL_CONTEXT, SummarySection.CONSTRAINTS
        };
        String text = render(draft);
        for (SummarySection section : droppable) {
            if (text.length() <= MAX_CHARS) {
                break;
            }
            dropped.put(section, Boolean.TRUE);
            text = render(draft, dropped);
        }
        if (text.length() > MAX_CHARS) {
            return text.substring(0, MAX_CHARS) + TRUNCATED;
        }
        return text.strip();
    }

    private String render(SummaryDraft draft, Map<SummarySection, Boolean> dropped) {
        StringBuilder text = new StringBuilder();
        for (SummarySection section : SummarySection.values()) {
            if (dropped.containsKey(section)) {
                continue;
            }
            if (section == SummarySection.CRITICAL_CONTEXT) {
                appendFiles(text, draft);
                continue;
            }
            appendSection(text, section, draft.section(section));
        }
        return text.toString();
    }
}
