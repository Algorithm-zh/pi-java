package com.piagent.application.context;

/**
 * 会话上下文状态，供头部仪表与手动压缩入口使用。
 *
 * <p>{@code availableTokens} 已扣除输出与协议预留，是真正可用于输入的额度；
 * 前端用它作为进度条上限，避免把 100% 误认为刚好触顶。</p>
 *
 * @param tokens 当前分支估算 token
 * @param contextWindow 模型上下文窗口
 * @param availableTokens 可用输入额度
 * @param maxOutputTokens 单次最大输出
 * @param modelProfileId 当前模型档案
 * @param modelName 模型名
 * @param provider 提供方
 */
public record ContextStatusView(int tokens, int contextWindow, int availableTokens,
        int maxOutputTokens, String modelProfileId, String modelName, String provider) {
}
