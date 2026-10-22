package com.piagent.trigger.http.governance;

import com.piagent.application.governance.readmodel.ToolPolicyView;
import java.util.Map;

/**
 * 工具策略 HTTP 响应。
 *
 * <p>{@code workspaceKey} 对用户全局规则固定为 {@code GLOBAL}；版本号用于
 * 前端提示并发修改，读取时只展示不写入。</p>
 *
 * @param id 主键
 * @param scope 作用域
 * @param workspaceKey 工作区键
 * @param toolName 工具名
 * @param effect 访问效果
 * @param approvalMode 审批模式
 * @param riskLevel 风险级别
 * @param timeoutMs 超时覆盖，可为 null
 * @param config 扩展配置
 * @param version 乐观锁版本
 */
public record ToolPolicyResponse(long id, String scope, String workspaceKey, String toolName,
        String effect, String approvalMode, String riskLevel, Integer timeoutMs,
        Map<String, Object> config, long version) {

    /**
     * 从读模型构造响应。
     *
     * @param view 读模型
     * @return 响应
     */
    public static ToolPolicyResponse from(ToolPolicyView view) {
        return new ToolPolicyResponse(view.id(), view.scope().name(), view.workspaceKey(),
                view.toolName(), view.effect().name(), view.approvalMode().name(),
                view.riskLevel().name(), view.timeoutMs(), view.config(), view.version());
    }
}
