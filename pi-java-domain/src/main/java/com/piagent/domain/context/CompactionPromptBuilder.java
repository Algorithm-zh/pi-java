package com.piagent.domain.context;

import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.message.AgentMessage;
import com.piagent.domain.session.model.message.AssistantMessage;
import com.piagent.domain.session.model.message.BranchSummaryMessage;
import com.piagent.domain.session.model.message.CompactionSummaryMessage;
import com.piagent.domain.session.model.message.ContentBlock;
import com.piagent.domain.session.model.message.TextBlock;
import com.piagent.domain.session.model.message.ThinkingBlock;
import com.piagent.domain.session.model.message.ToolCallBlock;
import com.piagent.domain.session.model.message.ToolResultMessage;
import com.piagent.domain.session.model.message.UserMessage;
import com.piagent.domain.session.model.payload.BranchSummaryPayload;
import com.piagent.domain.session.model.payload.CompactionPayload;
import com.piagent.domain.session.model.payload.CustomMessagePayload;
import com.piagent.domain.session.model.payload.MessagePayload;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 组装“请总结这段历史”的压缩请求内容。
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>摘要请求使用<b>独立的 user 消息</b>，不复用会话工具，也不要求模型调用工具——
 *       这样任何支持文本输出的模型都能完成压缩，不依赖 function calling；</li>
 *   <li>历史文本按“角色 + 内容”逐条拼接，工具调用与工具结果保留名字，但结果正文有上限，
 *       避免压缩请求本身超出上下文；</li>
 *   <li>上一次的摘要会作为“已有摘要”传给模型，要求在此基础上增量更新，
 *       而不是让模型从零开始重新编造。</li>
 * </ul>
 *
 * <p>纯函数、无状态。</p>
 */
public final class CompactionPromptBuilder {

    /** 单条工具结果进入压缩请求时的最大字符数。 */
    public static final int MAX_TOOL_RESULT_CHARS = 2_000;

    /** 历史正文总字符上限，超过后只保留最近内容并标注省略。 */
    public static final int MAX_HISTORY_CHARS = 48_000;

    private static final String OMITTED = "…（更早的内容已省略）";

    /**
     * 生成压缩请求的系统提示词。
     *
     * @return 系统提示词
     */
    public String systemPrompt() {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是上下文压缩器。你的任务是把一段较长的编码对话压缩成结构化的交接摘要，")
                .append("让另一个代理在不阅读原始对话的情况下也能继续工作。\n\n")
                .append("要求：\n")
                .append("1. 只输出摘要正文，不要寒暄、不要解释你在做什么；\n")
                .append("2. 严格使用下列二级标题，每个标题下用简短条目或短段落：\n");
        for (SummarySection section : SummarySection.values()) {
            prompt.append("   ## ").append(section.title()).append('\n');
        }
        prompt.append("3. “目标”写用户最终想达成什么；“下一步”写还没完成、接手后应当立刻做的事；\n")
                .append("4. 保留精确的文件路径、命令、错误信息与关键数值，不要改写或猜测；\n")
                .append("5. 不要编造对话中不存在的信息；信息缺失时写“未提及”；\n")
                .append("6. 总长度控制在 1200 字以内，宁可精简也不要重复。");
        return prompt.toString();
    }

    /**
     * 生成压缩请求的用户消息文本。
     *
     * @param entries 待摘要 entry，按时间顺序
     * @param splitTurnPrefix split-turn 前缀 entry；普通压缩传空列表
     * @param previousSummary 上一次压缩摘要，可为 null
     * @return 用户消息文本
     */
    public String userMessage(List<SessionEntry> entries, List<SessionEntry> splitTurnPrefix,
            String previousSummary) {
        Objects.requireNonNull(entries, "entries 不能为空");
        Objects.requireNonNull(splitTurnPrefix, "splitTurnPrefix 不能为空");
        StringBuilder message = new StringBuilder();
        message.append("请把下面的历史压缩为交接摘要。\n");
        if (previousSummary != null && !previousSummary.isBlank()) {
            message.append("\n## 已有摘要（请在此基础上更新，去掉已过时内容）\n")
                    .append(previousSummary.strip()).append('\n');
        }
        if (!splitTurnPrefix.isEmpty()) {
            message.append("\n注意：最后一个用户回合过长，被拆成两段。下面先给出该回合的前半段，需要你摘要；\n")
                    .append("该回合的后半段会原样保留在上下文里，不要在摘要中重复它。\n");
        }
        message.append("\n## 待摘要历史\n");
        List<String> lines = new ArrayList<>();
        for (SessionEntry entry : entries) {
            String line = render(entry);
            if (line != null) {
                lines.add(line);
            }
        }
        message.append(boundedHistory(lines));
        message.append("\n现在输出摘要正文。");
        return message.toString();
    }

    /**
     * 渲染单条 entry。
     *
     * @param entry entry
     * @return 文本；不参与上下文时返回 null
     */
    String render(SessionEntry entry) {
        return switch (entry.payload()) {
            case CompactionPayload payload -> "[历史摘要] " + payload.summary().strip();
            case BranchSummaryPayload payload -> "[分支摘要] " + payload.summary().strip();
            case CustomMessagePayload payload -> "[自定义消息] " + blocks(payload.content());
            case MessagePayload payload -> renderMessage(payload.message());
            default -> null;
        };
    }

    private String renderMessage(AgentMessage message) {
        return switch (message) {
            case UserMessage user -> "[用户] " + blocks(user.content());
            case AssistantMessage assistant -> "[助手]" + toolSuffix(assistant.content())
                    + " " + blocks(assistant.content());
            case ToolResultMessage result -> "[工具结果 " + result.toolName() + "] "
                    + truncate(blocks(result.content()), MAX_TOOL_RESULT_CHARS);
            case CompactionSummaryMessage summary -> "[历史摘要] " + summary.summary().strip();
            case BranchSummaryMessage branch -> "[分支摘要] " + branch.summary().strip();
        };
    }

    private String blocks(List<ContentBlock> content) {
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : content) {
            switch (block) {
                case TextBlock value -> text.append(value.text());
                case ThinkingBlock value -> {
                    // 思考过程不进入压缩请求：它通常是噪声，且可能极长。
                }
                case ToolCallBlock value -> text.append(" [调用 ").append(value.name()).append(']');
            }
        }
        return text.toString().strip();
    }

    private String toolSuffix(List<ContentBlock> content) {
        StringBuilder suffix = new StringBuilder();
        for (ContentBlock block : content) {
            if (block instanceof ToolCallBlock call) {
                suffix.append(" <").append(call.name()).append('>');
            }
        }
        return suffix.toString();
    }

    /**
     * 合并历史：从后向前保留，超过上限时丢弃最早的条目并加省略标记。
     */
    private String boundedHistory(List<String> lines) {
        int total = 0;
        int start = lines.size();
        for (int index = lines.size() - 1; index >= 0; index--) {
            int length = lines.get(index).length() + 1;
            if (total + length > MAX_HISTORY_CHARS) {
                break;
            }
            total += length;
            start = index;
        }
        StringBuilder text = new StringBuilder();
        if (start > 0) {
            text.append(OMITTED).append('\n');
        }
        for (int index = start; index < lines.size(); index++) {
            text.append(lines.get(index)).append('\n');
        }
        return text.toString();
    }

    private String truncate(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "\n…（已截断）";
    }

    /**
     * 把模型输出的小节标题映射为枚举；无法识别时返回 null。
     *
     * <p>兼容中英文标题与 “## 目标” / “目标:” / “Goal” 等常见写法，
     * 因为不同模型对标题格式的遵循程度差异很大。</p>
     *
     * @param rawTitle 标题文本
     * @return 小节；无法识别时返回 null
     */
    public static SummarySection parseSection(String rawTitle) {
        if (rawTitle == null) {
            return null;
        }
        String title = rawTitle.toLowerCase(Locale.ROOT)
                .replace("#", " ")
                .replace("*", " ")
                .replace(":", " ")
                .replace("：", " ")
                .replaceAll("\\s+", " ")
                .strip();
        for (SummarySection section : SummarySection.values()) {
            if (title.startsWith(section.title().toLowerCase(Locale.ROOT))
                    || title.startsWith(englishName(section))) {
                return section;
            }
        }
        return null;
    }

    private static String englishName(SummarySection section) {
        return switch (section) {
            case GOAL -> "goal";
            case CONSTRAINTS -> "constraint";
            case PROGRESS -> "progress";
            case DECISIONS -> "decision";
            case NEXT_STEPS -> "next step";
            case CRITICAL_CONTEXT -> "critical context";
        };
    }
}
