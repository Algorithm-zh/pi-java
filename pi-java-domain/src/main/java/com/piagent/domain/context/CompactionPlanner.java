package com.piagent.domain.context;

import com.piagent.domain.session.model.EntryType;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.message.UserMessage;
import com.piagent.domain.session.model.payload.BranchSummaryPayload;
import com.piagent.domain.session.model.payload.CompactionPayload;
import com.piagent.domain.session.model.payload.CustomMessagePayload;
import com.piagent.domain.session.model.payload.MessagePayload;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.shared.Guard;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 压缩规划器。
 *
 * <p>从最新 entry 向前保留 {@code keepRecentTokens}，切点只能落在参与上下文的
 * 消息上，并且跳过 toolResult：toolResult 依赖前面的 assistant toolCall，
 * 一旦被切到保留区开头，投影器会判定为孤立结果。</p>
 *
 * <p>若切点落在最后一个 user turn 内，说明单个 turn 本身超过保留额度，
 * 计划会标记 split-turn：摘要该 turn 的前缀，保留后半段继续执行。</p>
 *
 * <p>前置条件：调用方只在<b>确实需要压缩</b>时才调用本规划器；若内容仍能整体放进
 * 保留窗口（保留额度不小于当前上下文），说明再压缩也无法腾出空间，直接返回空计划，
 * 避免把极短的对话摘要成摘要。</p>
 *
 * <p>纯函数：不读数据库、不调用模型；当前上下文的 token 由调用方传入，
 * 便于使用 usage 锚定后的估算。</p>
 */
public final class CompactionPlanner {

    /**
     * 生成压缩计划。
     *
     * @param branch 当前分支，按时间顺序（根到叶）
     * @param currentTokens 当前上下文估算 token
     * @param budget 上下文预算
     * @return 计划；没有可摘要内容时为空
     */
    public Optional<CompactionPlan> plan(List<SessionEntry> branch, int currentTokens,
            ContextBudget budget) {
        Objects.requireNonNull(branch, "branch 不能为空");
        Objects.requireNonNull(budget, "budget 不能为空");
        Guard.require(currentTokens >= 0, DomainErrorCode.INVALID_ARGUMENT,
                "currentTokens 不能为负数");
        if (branch.size() < 2) {
            return Optional.empty();
        }
        int compactionIndex = lastCompactionIndex(branch);
        int start = compactionIndex < 0 ? 0 : firstKeptIndex(branch, compactionIndex);
        String previousSummary = compactionIndex < 0 ? null
                : ((CompactionPayload) branch.get(compactionIndex).payload()).summary();
        List<SessionEntry> visible = contextEntries(branch, start);
        if (visible.size() < 2) {
            return Optional.empty();
        }
        int cut = keepCut(visible, budget.keepRecentTokens());
        // keepCut 返回 0 且保留窗口覆盖整段内容时，说明压缩不会带来收益（摘要 + 保留区 ≈ 原文），
        // 直接放弃，交由调用方以“无法压缩”结束本次流程；返回 0 但内容确实超出窗口
        // （单条 entry 就超过保留额度）时，仍需至少摘要掉最早的一条。
        if (cut == 0) {
            cut = sumTokens(visible) <= budget.keepRecentTokens() ? -1 : 1;
            if (cut < 0) {
                return Optional.empty();
            }
        }
        if (cut >= visible.size()) {
            cut = visible.size() - 1;
        }
        while (cut < visible.size() && visible.get(cut).isToolResult()) {
            cut++;
        }
        if (cut >= visible.size()) {
            cut = lastNonToolResult(visible);
        }
        if (cut <= 0 || cut >= visible.size()) {
            return Optional.empty();
        }
        List<SessionEntry> summarized = List.copyOf(visible.subList(0, cut));
        List<SessionEntry> kept = visible.subList(cut, visible.size());
        List<SessionEntry> splitPrefix = splitTurnPrefix(visible, cut);
        return Optional.of(new CompactionPlan(summarized, splitPrefix, kept.get(0).id(),
                previousSummary, currentTokens, sumTokens(kept)));
    }

    private int lastCompactionIndex(List<SessionEntry> branch) {
        for (int index = branch.size() - 1; index >= 0; index--) {
            if (branch.get(index).type() == EntryType.COMPACTION) {
                return index;
            }
        }
        return -1;
    }

    private int firstKeptIndex(List<SessionEntry> branch, int compactionIndex) {
        CompactionPayload payload = (CompactionPayload) branch.get(compactionIndex).payload();
        for (int index = 0; index < compactionIndex; index++) {
            if (branch.get(index).id().equals(payload.firstKeptEntryId())) {
                return index;
            }
        }
        throw new DomainException(DomainErrorCode.INVALID_ENTRY,
                "既有压缩的保留起点不在压缩点之前: " + payload.firstKeptEntryId());
    }

    private List<SessionEntry> contextEntries(List<SessionEntry> branch, int start) {
        List<SessionEntry> visible = new ArrayList<>();
        for (int index = start; index < branch.size(); index++) {
            SessionEntry entry = branch.get(index);
            if (contributesContext(entry)) {
                visible.add(entry);
            }
        }
        return visible;
    }

    private boolean contributesContext(SessionEntry entry) {
        return entry.payload() instanceof MessagePayload
                || entry.payload() instanceof CustomMessagePayload
                || entry.payload() instanceof BranchSummaryPayload;
    }

    /**
     * 返回保留区的起始下标。
     *
     * <p>从后向前累计，累计超过 keepRecent 时停止；此前累计的 entry 组成保留区。
     * 若全部 entry 都能放进保留区，返回 0，表示无需压缩。</p>
     */
    private int keepCut(List<SessionEntry> visible, int keepRecentTokens) {
        int kept = 0;
        int cut = visible.size();
        for (int index = visible.size() - 1; index >= 0; index--) {
            int next = kept + visible.get(index).tokenEstimate();
            if (next > keepRecentTokens) {
                break;
            }
            kept = next;
            cut = index;
        }
        return cut;
    }

    private int lastNonToolResult(List<SessionEntry> visible) {
        for (int index = visible.size() - 1; index >= 0; index--) {
            if (!visible.get(index).isToolResult()) {
                return index;
            }
        }
        return visible.size();
    }

    private List<SessionEntry> splitTurnPrefix(List<SessionEntry> visible, int cut) {
        int lastUser = -1;
        for (int index = visible.size() - 1; index >= 0; index--) {
            SessionEntry entry = visible.get(index);
            if (entry.payload() instanceof MessagePayload payload
                    && payload.message() instanceof UserMessage) {
                lastUser = index;
                break;
            }
        }
        if (lastUser < 0 || lastUser >= cut) {
            return List.of();
        }
        return List.copyOf(visible.subList(lastUser, cut));
    }

    private int sumTokens(List<SessionEntry> entries) {
        long total = 0;
        for (SessionEntry entry : entries) {
            total += entry.tokenEstimate();
        }
        return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }
}
