package com.piagent.domain.context;

import com.piagent.domain.session.model.EntryId;
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
import com.piagent.domain.session.model.payload.EntryPayload;
import com.piagent.domain.session.model.payload.MessagePayload;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * entry 级 token 估算器。
 *
 * <p>为什么需要它：M1–M6 里“落库时估算”和“投影时汇总”各自写了一套字符估算，
 * 两套口径一旦漂移，压缩阈值就会与真实上下文长度脱节。M7 起所有 token 估算
 * 都从这里出发，保证“写进 entry 的数字”与“投影汇总的数字”同源。</p>
 *
 * <p>口径说明：</p>
 * <ul>
 *   <li>只有会进入模型上下文的 payload 才计入 token：消息、自定义消息、压缩摘要、分支摘要；
 *       审批、运行摘要、标签、模型切换等审计 entry 计为 0，不占上下文预算；</li>
 *   <li>文本按 {@link TokenEstimator} 的字符启发式（CJK 1:1，其余 4:1）；</li>
 *   <li>工具调用额外计入工具名与参数 JSON 的字符；</li>
 *   <li>参与上下文的 entry 至少计 1 token，避免空消息被估算为 0 后无限拟合。</li>
 * </ul>
 *
 * <p>纯函数、无状态、线程安全。</p>
 */
public final class EntryTokenCounter {

    private final TokenEstimator estimator;

    /**
     * 创建计数器。
     *
     * @param estimator 文本估算器
     */
    public EntryTokenCounter(TokenEstimator estimator) {
        this.estimator = Objects.requireNonNull(estimator, "estimator 不能为空");
    }

    /**
     * 创建使用默认估算器的计数器。
     */
    public EntryTokenCounter() {
        this(new TokenEstimator());
    }

    /**
     * 估算一条 entry 写入日志时使用的 token。
     *
     * @param payload entry 载荷
     * @return 估算值；不进入上下文的载荷为 0
     */
    public int count(EntryPayload payload) {
        Objects.requireNonNull(payload, "payload 不能为空");
        return switch (payload) {
            case MessagePayload message -> countMessage(message.message());
            case CustomMessagePayload custom -> atLeastOne(countBlocks(custom.content()));
            case CompactionPayload compaction -> atLeastOne(countText(compaction.summary()));
            case BranchSummaryPayload branch -> atLeastOne(countText(branch.summary()));
            default -> 0;
        };
    }

    /**
     * 估算一条消息。
     *
     * @param message 消息
     * @return 估算值，至少为 1
     */
    public int countMessage(AgentMessage message) {
        Objects.requireNonNull(message, "message 不能为空");
        return switch (message) {
            case UserMessage user -> atLeastOne(countBlocks(user.content()));
            case AssistantMessage assistant -> atLeastOne(countBlocks(assistant.content()));
            case ToolResultMessage result -> atLeastOne(countBlocks(result.content()));
            case CompactionSummaryMessage summary -> atLeastOne(countText(summary.summary()));
            case BranchSummaryMessage branch -> atLeastOne(countText(branch.summary()));
        };
    }

    /**
     * 估算纯文本。
     *
     * @param text 文本
     * @return 估算值，可能为 0
     */
    public int countText(String text) {
        return estimator.estimate(text).tokens();
    }

    /**
     * 汇总 entry 的落库估算值。
     *
     * @param entries entry 列表
     * @return 合计
     */
    public int sum(List<SessionEntry> entries) {
        Objects.requireNonNull(entries, "entries 不能为空");
        long total = 0;
        for (SessionEntry entry : entries) {
            total += Math.max(0, entry.tokenEstimate());
        }
        return cap(total);
    }

    /**
     * 汇总参与上下文的 entry。
     *
     * <p>只统计 {@code contextEntryIds} 中的 entry。投影器产出的 trace 正好是参与
     * 上下文的 entry 标识集合，用它过滤可以避免把审批、运行摘要等审计 entry 算进预算。</p>
     *
     * @param entries 分支 entry
     * @param contextEntryIds 参与上下文的 entry 标识
     * @return 合计
     */
    public int sumContext(List<SessionEntry> entries, Set<EntryId> contextEntryIds) {
        Objects.requireNonNull(entries, "entries 不能为空");
        Objects.requireNonNull(contextEntryIds, "contextEntryIds 不能为空");
        long total = 0;
        for (SessionEntry entry : entries) {
            if (contextEntryIds.contains(entry.id())) {
                total += Math.max(0, entry.tokenEstimate());
            }
        }
        return cap(total);
    }

    /**
     * 估算一组内容块。
     *
     * @param blocks 内容块
     * @return 估算值，可能为 0
     */
    public int countBlocks(List<ContentBlock> blocks) {
        Objects.requireNonNull(blocks, "blocks 不能为空");
        long cjk = 0;
        long other = 0;
        for (ContentBlock block : blocks) {
            String text = switch (block) {
                case TextBlock value -> value.text();
                case ThinkingBlock value -> value.thinking();
                case ToolCallBlock value -> value.name() + String.valueOf(value.arguments());
            };
            long[] chars = countChars(text);
            cjk += chars[0];
            other += chars[1];
        }
        return estimator.estimateChars(cjk, other).tokens();
    }

    private long[] countChars(String text) {
        return estimator.countChars(text);
    }

    private static int atLeastOne(int tokens) {
        return Math.max(1, tokens);
    }

    private static int cap(long total) {
        return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }
}
