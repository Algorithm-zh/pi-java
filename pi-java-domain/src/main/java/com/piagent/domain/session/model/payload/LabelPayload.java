package com.piagent.domain.session.model.payload;

import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.EntryType;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.util.Objects;

/**
 * 书签载荷。
 *
 * @param targetEntryId 被标记的 entry
 * @param label 标签；为 null 表示清除标签
 */
public record LabelPayload(EntryId targetEntryId, String label) implements EntryPayload {

    /**
     * 校验书签。
     *
     * @param targetEntryId 目标 entry
     * @param label 标签
     */
    public LabelPayload {
        Objects.requireNonNull(targetEntryId, "targetEntryId 不能为空");
        if (label != null) {
            Guard.maxLength(label, 128, DomainErrorCode.INVALID_ARGUMENT, "标签长度不能超过 128");
        }
    }

    @Override
    public EntryType entryType() {
        return EntryType.LABEL;
    }
}
