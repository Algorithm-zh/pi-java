package com.piagent.application.agent.service;

import com.piagent.application.context.CompactionMode;
import com.piagent.domain.agent.RunId;
import com.piagent.domain.context.ContextBudget;
import com.piagent.domain.model.ModelProfile;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.SessionEntry;
import java.util.List;
import java.util.Objects;

/**
 * 一次压缩调用的输入。
 *
 * <p>压缩步骤需要“会话、运行、模型、预算、分支、触发方式、原因、事件发布器”八项信息，
 * 直接展开必然是超长参数列表。打包成值对象后，内部方法签名只有“调用 + 状态 + 测量”
 * 三項，职责也更清晰。</p>
 *
 * @param session 会话快照
 * @param runId 运行标识
 * @param profile 模型档案
 * @param budget 上下文预算
 * @param branch 当前分支
 * @param mode 触发方式
 * @param reason 触发原因
 * @param events 事件发布器
 */
record CompactionInvocation(
        ChatSession session,
        RunId runId,
        ModelProfile profile,
        ContextBudget budget,
        List<SessionEntry> branch,
        CompactionMode mode,
        String reason,
        RunEventPublisher events) {

    /**
     * 校验调用参数。
     *
     * @param session 会话
     * @param runId 运行标识
     * @param profile 模型档案
     * @param budget 预算
     * @param branch 分支
     * @param mode 触发方式
     * @param reason 原因
     * @param events 事件发布器
     */
    CompactionInvocation {
        Objects.requireNonNull(session, "session 不能为空");
        Objects.requireNonNull(runId, "runId 不能为空");
        Objects.requireNonNull(profile, "profile 不能为空");
        Objects.requireNonNull(budget, "budget 不能为空");
        Objects.requireNonNull(mode, "mode 不能为空");
        Objects.requireNonNull(events, "events 不能为空");
        branch = branch == null ? List.of() : List.copyOf(branch);
        reason = reason == null ? "" : reason;
    }
}
