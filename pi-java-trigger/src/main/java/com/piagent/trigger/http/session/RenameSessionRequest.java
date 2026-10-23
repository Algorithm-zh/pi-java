package com.piagent.trigger.http.session;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;

/**
 * 重命名会话请求。
 *
 * @param title 新标题
 */
public record RenameSessionRequest(String title) {

    /**
     * 校验标题非空，具体长度由领域层再次校验。
     *
     * @param title 新标题
     */
    public RenameSessionRequest {
        if (title == null || title.isBlank()) {
            throw new DomainException(DomainErrorCode.INVALID_ARGUMENT, "title 不能为空");
        }
    }
}
