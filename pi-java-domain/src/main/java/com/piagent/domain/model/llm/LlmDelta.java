package com.piagent.domain.model.llm;

/**
 * 流式模型输出的增量事件。
 *
 * <p>正常结束前必须发出一个 {@link DoneDelta}；随后 {@link LlmStream#response()}
 * 才可用。传输错误以 {@link LlmGatewayException} 抛出，不伪造错误 delta。</p>
 */
public sealed interface LlmDelta permits TextDelta, ThinkingDelta, ToolCallDelta, DoneDelta {
}
