package com.piagent.application.plan;

import com.piagent.domain.plan.model.Plan;
import com.piagent.domain.plan.model.PlanItemStatus;
import com.piagent.domain.session.model.payload.VerificationPayload;

/**
 * 会话当前的计划与最近一次验证。
 *
 * @param plan 当前计划；没有记录时为空计划
 * @param verification 最近一次验证；从未验证时为空
 */
public record SessionPlanSnapshot(Plan plan, VerificationPayload verification) {

    /**
     * 已完成条数。
     *
     * @return 完成数
     */
    public long completed() {
        return plan.items().stream()
                .filter(item -> item.status() == PlanItemStatus.DONE)
                .count();
    }

    /**
     * 是否有计划。
     *
     * @return true 表示至少一条待办
     */
    public boolean hasPlan() {
        return !plan.isEmpty();
    }
}
