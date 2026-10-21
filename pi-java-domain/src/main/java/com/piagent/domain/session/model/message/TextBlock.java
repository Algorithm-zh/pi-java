package com.piagent.domain.session.model.message;

import java.util.Objects;

/**
 * 纯文本内容块。
 *
 * @param text 文本，允许为空串（流式增量可能为空），但不能为 null
 */
public record TextBlock(String text) implements ContentBlock {

    /**
     * 校验文本块。
     *
     * @param text 文本
     */
    public TextBlock {
        Objects.requireNonNull(text, "text 不能为空");
    }
}
