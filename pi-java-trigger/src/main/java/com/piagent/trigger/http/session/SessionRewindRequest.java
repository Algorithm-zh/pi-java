package com.piagent.trigger.http.session;

import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;

/**
 * 回退请求。
 *
 * @param checkpointEntryId 目标检查点 entry
 * @param dryRun 是否只预览；缺省为 true
 * @param note 可选备注，最多 200 字
 */
public record SessionRewindRequest(String checkpointEntryId, Boolean dryRun, String note) {

    /**
     * 解析检查点标识。
     *
     * @return 检查点 entry
     */
    public EntryId requireCheckpointId() {
        if (checkpointEntryId == null || checkpointEntryId.isBlank()) {
            throw new DomainException(DomainErrorCode.INVALID_ARGUMENT,
                    "checkpointEntryId 不能为空");
        }
        try {
            return EntryId.parse(checkpointEntryId);
        } catch (RuntimeException error) {
            throw new DomainException(DomainErrorCode.INVALID_ARGUMENT,
                    "checkpointEntryId 不是合法的 ULID");
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
