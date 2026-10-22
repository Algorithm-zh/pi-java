package com.piagent.application.context;

import com.piagent.domain.session.model.EntryId;

/**
 * 手动压缩结果视图。
 *
 * @param applied 是否真的压缩
 * @param entryId 摘要 entry；未压缩时为 null
 * @param tokensBefore 压缩前估算
 * @param tokensAfter 压缩后估算
 * @param summary 摘要正文；未压缩时为 null
 * @param prunedOnly 是否降级为只剪枝
 * @param message 给用户的说明（跳过原因或结果描述）
 */
public record ManualCompactionView(
        boolean applied,
        EntryId entryId,
        int tokensBefore,
        int tokensAfter,
        String summary,
        boolean prunedOnly,
        String message) {

    /**
     * 构造成功视图。
     *
     * @param entryId 摘要 entry
     * @param tokensBefore 压缩前
     * @param tokensAfter 压缩后
     * @param summary 摘要
     * @param prunedOnly 是否只剪枝
     * @return 视图
     */
    public static ManualCompactionView applied(EntryId entryId, int tokensBefore, int tokensAfter,
            String summary, boolean prunedOnly) {
        String message = prunedOnly
                ? "压缩已写入，但摘要收敛不足（摘要较长），已标记 prunedOnly"
                : "压缩完成，上下文由 " + tokensBefore + " token 降至 " + tokensAfter + " token";
        return new ManualCompactionView(true, entryId, tokensBefore, tokensAfter, summary,
                prunedOnly, message);
    }

    /**
     * 构造跳过视图。
     *
     * @param tokensBefore 压缩前估算
     * @param reason 跳过原因
     * @return 视图
     */
    public static ManualCompactionView skipped(int tokensBefore, String reason) {
        return new ManualCompactionView(false, null, tokensBefore, tokensBefore, null, false,
                "未执行压缩：" + reason);
    }
}
