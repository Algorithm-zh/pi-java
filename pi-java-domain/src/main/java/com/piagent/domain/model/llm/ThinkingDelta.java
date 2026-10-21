package com.piagent.domain.model.llm;

import java.util.Objects;

/**
 * 思考内容增量。
 *
 * @param text 新增思考文本，可为空字符串
 */
public record ThinkingDelta(String text) implements LlmDelta {

    /**
     * 校验思考增量。
     *
     * @param text 文本
     */
    public ThinkingDelta {
        Objects.requireNonNull(text, "text 不能为空");
    }
}
