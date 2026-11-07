package com.piagent.trigger.http.session;

import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;

/**
 * 分支切换请求。
 *
 * @param branchEntryId 当前分支上的分支简报
 * @param dryRun 是否只预览；缺省为 true
 * @param note 可选备注，最多 200 字
 */
public record SessionResumeRequest(String branchEntryId, Boolean dryRun, String note) {

    /**
     * 解析分支简报标识。
     *
     * @return 分支简报 entry
     */
    public EntryId requireBranchEntryId() {
        if (branchEntryId == null || branchEntryId.isBlank()) {
            throw new DomainException(DomainErrorCode.INVALID_ARGUMENT,
                    "branchEntryId 不能为空");
        }
        try {
            return EntryId.parse(branchEntryId);
        } catch (RuntimeException error) {
            throw new DomainException(DomainErrorCode.INVALID_ARGUMENT,
                    "branchEntryId 不是合法的 ULID");
        }
    }

    /**
     * 是否只预览；未提供时按预览处理。
     *
     * @return true 表示只预览
     */
    public boolean preview() {
        return dryRun == null || dryRun;
    }
}
