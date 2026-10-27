package com.piagent.domain.model;

/**
 * 模型能力声明。
 *
 * <p>能力用于选择阶段的快速校验与 UI 提示，不代表运行时一定成功；
 * provider 仍可能因账号或模型版本拒绝某项能力。</p>
 *
 * @param streaming 是否支持流式输出
 * @param toolCalls 是否支持工具调用
 * @param thinking 是否支持思考内容
 * @param vision 是否支持图片输入
 */
public record ModelCapabilities(boolean streaming, boolean toolCalls, boolean thinking,
        boolean vision) {

    /**
     * 兼容未声明图片能力的旧调用方。
     *
     * @param streaming 是否支持流式输出
     * @param toolCalls 是否支持工具调用
     * @param thinking 是否支持思考内容
     */
    public ModelCapabilities(boolean streaming, boolean toolCalls, boolean thinking) {
        this(streaming, toolCalls, thinking, false);
    }

    /**
     * 默认能力：流式 + 工具调用，不假设思考与图片能力。
     *
     * @return 默认能力
     */
    public static ModelCapabilities defaults() {
        return new ModelCapabilities(true, true, false, false);
    }

    /**
     * 离线 Fake 演示能力：额外允许图片，便于零配置演示多模态流程。
     *
     * @return Fake 能力
     */
    public static ModelCapabilities demo() {
        return new ModelCapabilities(true, true, false, true);
    }
}
