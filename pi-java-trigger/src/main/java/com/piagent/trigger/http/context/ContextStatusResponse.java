package com.piagent.trigger.http.context;

import com.piagent.application.context.ContextStatusView;

/**
 * 会话上下文状态响应。
 *
 * @param tokens 当前估算 token
 * @param contextWindow 模型上下文窗口
 * @param availableTokens 可用输入额度
 * @param maxOutputTokens 单次最大输出
 * @param modelProfileId 当前模型档案
 * @param modelName 模型名
 * @param provider 提供方
 */
public record ContextStatusResponse(int tokens, int contextWindow, int availableTokens,
        int maxOutputTokens, String modelProfileId, String modelName, String provider) {

    /**
     * 从应用层视图转换。
     *
     * @param view 上下文状态
     * @return 响应
     */
    public static ContextStatusResponse from(ContextStatusView view) {
        return new ContextStatusResponse(view.tokens(), view.contextWindow(),
                view.availableTokens(), view.maxOutputTokens(), view.modelProfileId(),
                view.modelName(), view.provider());
    }
}
