package com.piagent.domain.model.llm;

import java.util.Objects;

/**
 * 文本增量。
 *
 * @param text 新增文本，可为空字符串
 */
public record TextDelta(String text) implements LlmDelta {

    /**
     * 校验文本增量。
     *
     * @param text 文本
     */
    public TextDelta {
        Objects.requireNonNull(text, "text 不能为空");
    }
}
