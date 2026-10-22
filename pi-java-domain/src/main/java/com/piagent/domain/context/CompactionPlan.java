package com.piagent.domain.context;

import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.util.List;
import java.util.Objects;

/**
 * 一次压缩的不可变计划。
 *
 * <p>{@code firstKeptEntryId} 是压缩后保留的第一条 entry，由投影器用来跳过
 * 被摘要的历史；{@code splitTurnPrefix} 非空表示切点落在单个超长 turn 内，
 * 只摘要该 turn 的前缀，保留其后半段继续执行。</p>
 *
 * @param entriesToSummarize 将被摘要的 entry，按时间顺序
 * @param splitTurnPrefix 被切开的当前 turn 前缀；普通压缩为空
 * @param firstKeptEntryId 压缩后保留的第一条 entry
 * @param previousSummary 上一次压缩摘要，可为 null
 * @param tokensBefore 压缩前上下文估算 token
 * @param tokensToKeep 压缩后保留的近期 token 估算
 */
public record CompactionPlan(
        List<SessionEntry> entriesToSummarize,
        List<SessionEntry> splitTurnPrefix,
        EntryId firstKeptEntryId,
        String previousSummary,
        int tokensBefore,
        int tokensToKeep) {

    /**
     * 校验计划。
     *
     * @param entriesToSummarize 待摘要 entry
     * @param splitTurnPrefix split-turn 前缀
     * @param firstKeptEntryId 保留起点
     * @param previousSummary 旧摘要
     * @param tokensBefore 压缩前 token
     * @param tokensToKeep 保留 token
     */
    public CompactionPlan {
        Objects.requireNonNull(entriesToSummarize, "entriesToSummarize 不能为空");
        Guard.require(!entriesToSummarize.isEmpty(), DomainErrorCode.INVALID_ARGUMENT,
                "待摘要 entry 不能为空");
        Objects.requireNonNull(splitTurnPrefix, "splitTurnPrefix 不能为空");
        Objects.requireNonNull(firstKeptEntryId, "firstKeptEntryId 不能为空");
        Guard.require(tokensBefore >= 0, DomainErrorCode.INVALID_ARGUMENT,
                "tokensBefore 不能为负数");
        Guard.require(tokensToKeep >= 0, DomainErrorCode.INVALID_ARGUMENT,
                "tokensToKeep 不能为负数");
        previousSummary = previousSummary == null || previousSummary.isBlank()
                ? null : previousSummary;
        entriesToSummarize = List.copyOf(entriesToSummarize);
        splitTurnPrefix = List.copyOf(splitTurnPrefix);
    }

    /**
     * 是否切开了单个 turn。
     *
     * @return true 表示存在 split-turn 前缀
     */
    public boolean splitTurn() {
        return !splitTurnPrefix.isEmpty();
    }

    /**
     * 被摘要 entry 的 token 估算合计。
     *
     * @return token 合计
     */
    public int summarizedTokens() {
        long total = 0;
        for (SessionEntry entry : entriesToSummarize) {
            total += entry.tokenEstimate();
        }
        return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }
}
