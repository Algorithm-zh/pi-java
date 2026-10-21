package com.piagent.domain.session.model.message;

import com.piagent.domain.session.model.EntryId;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.time.Instant;
import java.util.Objects;

/**
 * 由 BRANCH_SUMMARY entry 投影出的旧分支摘要消息。
 *
 * @param summary 摘要
 * @param fromEntryId 离开的节点
 * @param timestamp 摘要时间
 */
public record BranchSummaryMessage(String summary, EntryId fromEntryId, Instant timestamp)
        implements AgentMessage {

    /**
     * 校验分支摘要。
     *
     * @param summary 摘要
     * @param fromEntryId 离开节点
     * @param timestamp 时间
     */
    public BranchSummaryMessage {
        Guard.notBlank(summary, DomainErrorCode.INVALID_ARGUMENT, "分支摘要不能为空");
        Objects.requireNonNull(fromEntryId, "fromEntryId 不能为空");
        Objects.requireNonNull(timestamp, "timestamp 不能为空");
    }
}
