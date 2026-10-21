package com.piagent.domain.model.llm;

import com.piagent.domain.model.Usage;
import com.piagent.domain.session.model.message.AssistantStopReason;
import com.piagent.domain.session.model.message.ContentBlock;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.util.List;
import java.util.Objects;

/**
 * 一次模型调用的最终响应。
 *
 * <p>内容块已归并完整：文本与思考块合并，工具调用参数已解析为 Map。
 * 若 {@code stopReason} 为 {@link AssistantStopReason#ERROR}，
 * {@code errorMessage} 必须提供。</p>
 *
 * @param content 完整内容块
 * @param usage token 用量
 * @param stopReason 停止原因
 * @param errorMessage 错误消息，正常时为 null
 */
public record LlmResponse(
        List<ContentBlock> content,
        Usage usage,
        AssistantStopReason stopReason,
        String errorMessage) {

    /**
     * 校验并冻结响应。
     *
     * @param content 内容块
     * @param usage 用量
     * @param stopReason 停止原因
     * @param errorMessage 错误消息
     */
    public LlmResponse {
        Objects.requireNonNull(content, "content 不能为空");
        Objects.requireNonNull(usage, "usage 不能为空");
        Objects.requireNonNull(stopReason, "stopReason 不能为空");
        content = List.copyOf(content);
        if (stopReason == AssistantStopReason.ERROR) {
            errorMessage = Guard.notBlank(errorMessage, DomainErrorCode.INVALID_ARGUMENT,
                    "错误响应必须包含 errorMessage");
        }
    }

    /**
     * 构造错误响应。
     *
     * @param usage 用量
     * @param errorMessage 错误消息
     * @return 错误响应
     */
    public static LlmResponse error(Usage usage, String errorMessage) {
        return new LlmResponse(List.of(), usage, AssistantStopReason.ERROR, errorMessage);
    }
}
