package com.piagent.domain.session.model.message;

import java.util.Objects;

/**
 * 模型思考内容块。
 *
 * @param thinking 思考文本
 * @param signature 提供方返回的签名，可为 null
 */
public record ThinkingBlock(String thinking, String signature) implements ContentBlock {

    /**
     * 校验思考块。
     *
     * @param thinking 思考文本
     * @param signature 签名
     */
    public ThinkingBlock {
        Objects.requireNonNull(thinking, "thinking 不能为空");
    }
}
