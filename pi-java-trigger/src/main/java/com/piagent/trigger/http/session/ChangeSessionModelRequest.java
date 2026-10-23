package com.piagent.trigger.http.session;

import com.piagent.domain.session.model.ThinkingLevel;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.util.Locale;

/**
 * 切换会话模型请求。
 *
 * @param modelProfileId 目标模型档案
 * @param thinkingLevel 新思考级别；省略时沿用当前级别
 */
public record ChangeSessionModelRequest(String modelProfileId, String thinkingLevel) {

    /**
     * 解析可选思考级别。
     *
     * @return 思考级别；未提供时为 null
     */
    public ThinkingLevel thinkingLevelOrNull() {
        if (thinkingLevel == null || thinkingLevel.isBlank()) {
            return null;
        }
        try {
            return ThinkingLevel.valueOf(thinkingLevel.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new DomainException(DomainErrorCode.INVALID_ARGUMENT,
                    "未知的思考级别: " + thinkingLevel);
        }
    }
}
