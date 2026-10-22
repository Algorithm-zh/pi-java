package com.piagent.application.agent.service;

import com.piagent.domain.model.llm.LlmResponse;
import java.util.Objects;

/**
 * 一次流式模型调用的结果。
 *
 * <p>正常结束一定有 {@code response}；取消时 {@code response} 为 null，
 * 但保留已经流出的文本与思考片段，便于把中断现场写入会话历史。
 *
 * @param response 完整响应；取消时为 null
 * @param streamedText 已流出的文本
 * @param streamedThinking 已流出的思考内容
 * @param cancelled 是否被取消
 */
public record TurnResult(LlmResponse response, String streamedText, String streamedThinking,
        boolean cancelled) {

    /**
     * 校验结果。
     *
     * @param response 完整响应
     * @param streamedText 已流出文本
     * @param streamedThinking 已流出思考
     * @param cancelled 是否取消
     */
    public TurnResult {
        streamedText = streamedText == null ? "" : streamedText;
        streamedThinking = streamedThinking == null ? "" : streamedThinking;
        if (cancelled) {
            if (response != null) {
                throw new IllegalArgumentException("取消结果不能包含完整响应");
            }
        } else {
            Objects.requireNonNull(response, "正常结束必须包含响应");
        }
    }

    /**
     * 正常结束结果。
     *
     * @param response 完整响应
     * @param text 已流出文本
     * @param thinking 已流出思考
     * @return 结果
     */
    public static TurnResult completed(LlmResponse response, String text, String thinking) {
        return new TurnResult(response, text, thinking, false);
    }

    /**
     * 取消结果。
     *
     * @param text 已流出文本
     * @param thinking 已流出思考
     * @return 结果
     */
    public static TurnResult cancelled(String text, String thinking) {
        return new TurnResult(null, text, thinking, true);
    }
}
