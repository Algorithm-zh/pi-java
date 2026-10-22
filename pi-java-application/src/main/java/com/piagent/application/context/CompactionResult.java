package com.piagent.application.context;

import com.piagent.domain.context.CompactionOutcome;
import com.piagent.domain.session.model.EntryId;

/**
 * 一次压缩的执行结果。
 *
 * <p>{@code skippedReason} 非空表示没有压缩（内容太少、已压缩且无新增、摘要失败等），
 * 此时其余字段为 null/0。调用方应把跳过原因返回给用户或写入日志，而不是静默继续。</p>
 *
 * @param applied 是否真的写入了 COMPACTION entry
 * @param entryId 新 entry 标识；未压缩时为 null
 * @param summary 摘要正文；未压缩时为 null
 * @param outcome 收敛判定；未压缩时为 null
 * @param prunedOnly 是否降级为“只剪枝”
 * @param skippedReason 跳过原因；压缩成功时为 null
 */
public record CompactionResult(
        boolean applied,
        EntryId entryId,
        String summary,
        CompactionOutcome outcome,
        boolean prunedOnly,
        String skippedReason) {

    /**
     * 构造成功结果。
     *
     * @param entryId entry 标识
     * @param summary 摘要
     * @param outcome 收敛判定
     * @param prunedOnly 是否只剪枝
     * @return 结果
     */
    public static CompactionResult applied(EntryId entryId, String summary,
            CompactionOutcome outcome, boolean prunedOnly) {
        return new CompactionResult(true, entryId, summary, outcome, prunedOnly, null);
    }

    /**
     * 构造跳过结果。
     *
     * @param reason 原因
     * @return 结果
     */
    public static CompactionResult skipped(String reason) {
        return new CompactionResult(false, null, null, null, false, reason);
    }
}
