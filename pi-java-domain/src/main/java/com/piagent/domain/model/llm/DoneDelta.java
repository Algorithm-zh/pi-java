package com.piagent.domain.model.llm;

import com.piagent.domain.session.model.message.AssistantStopReason;
import java.util.Objects;

/**
 * 流正常结束标记。
 *
 * <p>用完该 delta 后，{@link LlmStream#response()} 应返回完整的最终响应。</p>
 *
 * @param stopReason 模型停止原因
 */
public record DoneDelta(AssistantStopReason stopReason) implements LlmDelta {

    /**
     * 校验结束标记。
     *
     * @param stopReason 停止原因
     */
    public DoneDelta {
        Objects.requireNonNull(stopReason, "stopReason 不能为空");
    }
}
