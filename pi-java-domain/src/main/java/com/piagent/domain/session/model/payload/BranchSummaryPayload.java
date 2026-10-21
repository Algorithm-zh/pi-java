package com.piagent.domain.session.model.payload;

import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.EntryType;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.util.Map;
import java.util.Objects;

/**
 * 分支切换摘要载荷。
 *
 * @param fromEntryId 被离开的分支节点
 * @param summary 摘要
 * @param details 扩展细节
 */
public record BranchSummaryPayload(
        EntryId fromEntryId,
        String summary,
        Map<String, Object> details) implements EntryPayload {

    /**
     * 校验分支摘要。
     *
     * @param fromEntryId 离开节点
     * @param summary 摘要
     * @param details 细节
     */
    public BranchSummaryPayload {
        Objects.requireNonNull(fromEntryId, "fromEntryId 不能为空");
        Guard.notBlank(summary, DomainErrorCode.INVALID_ARGUMENT, "分支摘要不能为空");
        Objects.requireNonNull(details, "details 不能为空");
        details = Map.copyOf(details);
    }

    @Override
    public EntryType entryType() {
        return EntryType.BRANCH_SUMMARY;
    }
}
