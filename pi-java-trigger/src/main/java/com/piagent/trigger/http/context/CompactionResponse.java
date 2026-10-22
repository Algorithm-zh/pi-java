package com.piagent.trigger.http.context;

import com.piagent.application.context.ManualCompactionView;

/**
 * 手动压缩的 HTTP 响应。
 *
 * @param applied 是否真的压缩
 * @param entryId 摘要 entry；未压缩时为 null
 * @param tokensBefore 压缩前估算
 * @param tokensAfter 压缩后估算
 * @param savedTokens 节省 token
 * @param prunedOnly 是否只在剪枝
 * @param summary 摘要正文；未压缩时为 null
 * @param message 给用户的说明
 */
public record CompactionResponse(
        boolean applied,
        String entryId,
        int tokensBefore,
        int tokensAfter,
        int savedTokens,
        boolean prunedOnly,
        String summary,
        String message) {

    /**
     * 转换用例结果。
     *
     * @param view 用例结果
     * @return 响应
     */
    public static CompactionResponse from(ManualCompactionView view) {
        return new CompactionResponse(view.applied(),
                view.entryId() == null ? null : view.entryId().toString(),
                view.tokensBefore(), view.tokensAfter(),
                Math.max(0, view.tokensBefore() - view.tokensAfter()),
                view.prunedOnly(), view.summary(), view.message());
    }
}
