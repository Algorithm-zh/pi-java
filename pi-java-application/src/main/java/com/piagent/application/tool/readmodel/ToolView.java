package com.piagent.application.tool.readmodel;

import com.piagent.domain.governance.model.PolicyEffect;
import com.piagent.domain.tool.model.ExecutionMode;
import com.piagent.domain.tool.model.ToolDescriptor;
import com.piagent.domain.tool.model.ToolRisk;

/**
 * 工具目录读模型：工具定义 + 当前工作区解析后的访问效果。
 *
 * <p>只给 API 与 UI 展示，不参与工具执行；执行时应用层会重新解析策略，
 * 不信任前端回传的值。</p>
 *
 * @param name 工具名
 * @param description 能力描述
 * @param inputSchemaJson 参数 JSON Schema
 * @param risk 风险级别
 * @param executionMode 执行模式
 * @param timeoutMs 超时毫秒
 * @param effect 当前工作区的访问效果
 */
public record ToolView(String name, String description, String inputSchemaJson, ToolRisk risk,
        ExecutionMode executionMode, long timeoutMs, PolicyEffect effect) {

    /**
     * 是否需要用户审批。
     *
     * @return true 表示 ASK
     */
    public boolean requiresApproval() {
        return effect == PolicyEffect.ASK;
    }

    /**
     * 从工具定义与策略效果构造读模型。
     *
     * @param descriptor 工具定义
     * @param effect 策略效果
     * @return 读模型
     */
    public static ToolView from(ToolDescriptor descriptor, PolicyEffect effect) {
        return new ToolView(descriptor.name().value(), descriptor.description(),
                descriptor.inputSchemaJson(), descriptor.risk(), descriptor.executionMode(),
                descriptor.timeout().toMillis(), effect);
    }
}
