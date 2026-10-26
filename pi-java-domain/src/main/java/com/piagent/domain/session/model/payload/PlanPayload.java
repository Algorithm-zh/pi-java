package com.piagent.domain.session.model.payload;

import com.piagent.domain.plan.model.Plan;
import com.piagent.domain.plan.model.PlanItem;
import com.piagent.domain.session.model.EntryType;
import java.util.List;

/**
 * 一次完整的待办快照。
 *
 * <p>每次更新都追加整份计划，而不是增量事件：读取分支上最后一条即可恢复当前
 * 清单，分支切换、重启与压缩都不会丢失语义。</p>
 *
 * @param runId 产生快照的 run；手工更新时为空字符串
 * @param items 待办列表
 */
public record PlanPayload(String runId, List<PlanItem> items) implements EntryPayload {

    /**
     * 规范化可空字段。
     *
     * @param runId run 标识
     * @param items 待办
     */
    public PlanPayload {
        runId = runId == null ? "" : runId;
        items = items == null ? List.of() : List.copyOf(items);
    }

    /**
     * 转为领域计划。
     *
     * @return 计划
     */
    public Plan plan() {
        return new Plan(items);
    }

    @Override
    public EntryType entryType() {
        return EntryType.PLAN;
    }
}
