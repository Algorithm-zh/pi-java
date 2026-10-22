package com.piagent.application.context;

import com.piagent.domain.context.ContextBudget;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionId;
import java.util.List;
import java.util.Objects;

/**
 * 一次压缩请求的输入。
 *
 * <p>用 record 收敛参数，避免服务方法出现超长参数列表：会话、分支、预算三者
 * 语义不同，拆开传容易错位。</p>
 *
 * @param sessionId 会话标识
 * @param mode 触发方式
 * @param reason 触发原因（中文，进入事件与日志）
 * @param budget 上下文预算
 * @param currentTokens 压缩前的上下文估算
 * @param branch 当前分支 entry，按时间顺序
 * @param lastCompactedTokens 上一次压缩时的上下文估算；从未压缩为 0
 */
public record CompactionRequest(
        SessionId sessionId,
        CompactionMode mode,
        String reason,
        ContextBudget budget,
        int currentTokens,
        List<SessionEntry> branch,
        int lastCompactedTokens) {

    /**
     * 校验请求。
     *
     * @param sessionId 会话
     * @param mode 触发方式
     * @param reason 原因
     * @param budget 预算
     * @param currentTokens 当前估算
     * @param branch 分支
     * @param lastCompactedTokens 上次压缩估算
     */
    public CompactionRequest {
        Objects.requireNonNull(sessionId, "sessionId 不能为空");
        Objects.requireNonNull(mode, "mode 不能为空");
        Objects.requireNonNull(budget, "budget 不能为空");
        Objects.requireNonNull(branch, "branch 不能为空");
        reason = reason == null ? "" : reason;
        branch = List.copyOf(branch);
    }
}
