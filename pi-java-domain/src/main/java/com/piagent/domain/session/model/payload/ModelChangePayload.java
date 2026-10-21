package com.piagent.domain.session.model.payload;

import com.piagent.domain.session.model.ThinkingLevel;
import com.piagent.domain.session.model.EntryType;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.util.Objects;

/**
 * 模型或思考级别切换载荷。
 *
 * @param modelProfileId 新模型档案 ID
 * @param thinkingLevel 新思考级别
 */
public record ModelChangePayload(String modelProfileId, ThinkingLevel thinkingLevel) implements EntryPayload {

    /**
     * 校验模型切换。
     *
     * @param modelProfileId 模型档案
     * @param thinkingLevel 思考级别
     */
    public ModelChangePayload {
        Guard.notBlank(modelProfileId, DomainErrorCode.INVALID_ARGUMENT, "modelProfileId 不能为空");
        Objects.requireNonNull(thinkingLevel, "thinkingLevel 不能为空");
    }

    @Override
    public EntryType entryType() {
        return EntryType.MODEL_CHANGE;
    }
}
