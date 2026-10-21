package com.piagent.domain.session.model.payload;

import com.piagent.domain.session.model.EntryType;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;

/**
 * 会话展示信息载荷。
 *
 * @param name 展示名
 */
public record SessionInfoPayload(String name) implements EntryPayload {

    /**
     * 校验展示名。
     *
     * @param name 展示名
     */
    public SessionInfoPayload {
        Guard.notBlank(name, DomainErrorCode.INVALID_ARGUMENT, "会话名不能为空");
        Guard.maxLength(name, 255, DomainErrorCode.INVALID_ARGUMENT, "会话名长度不能超过 255");
    }

    @Override
    public EntryType entryType() {
        return EntryType.SESSION_INFO;
    }
}
