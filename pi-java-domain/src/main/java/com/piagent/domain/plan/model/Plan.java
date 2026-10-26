package com.piagent.domain.plan.model;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.util.List;
import java.util.OptionalInt;

/**
 * 一次任务的待办快照。
 *
 * <p>计划不可变，每次更新写入一条完整的 PLAN entry，读取时取分支上最后一条。
 * 这样重启、切换分支或被压缩后都能还原“当时认为要做的事”，也不会因为
 * 增量事件丢失中间状态。</p>
 *
 * @param items 按执行顺序排列的待办；可为空表示清空计划
 */
public record Plan(List<PlanItem> items) {

    /** 单次计划的最大条目数，避免模型把长文拆成几十条刷屏。 */
    public static final int MAX_ITEMS = 20;

    /**
     * 校验并冻结计划。
     *
     * @param items 待办
     */
    public Plan {
        items = items == null ? List.of() : List.copyOf(items);
        Guard.require(items.size() <= MAX_ITEMS, DomainErrorCode.INVALID_ARGUMENT,
                "待办不能超过 " + MAX_ITEMS + " 条");
        long inProgress = items.stream()
                .filter(item -> item.status() == PlanItemStatus.IN_PROGRESS)
                .count();
        Guard.require(inProgress <= 1, DomainErrorCode.INVALID_ARGUMENT,
                "同时只能有一条进行中的待办");
    }

    /**
     * 空计划。
     *
     * @return 空计划
     */
    public static Plan empty() {
        return new Plan(List.of());
    }

    /**
     * 是否为空。
     *
     * @return true 表示没有待办
     */
    public boolean isEmpty() {
        return items.isEmpty();
    }

    /**
     * 已完成条数。
     *
     * @return 完成数
     */
    public long completedCount() {
        return items.stream().filter(item -> item.status() == PlanItemStatus.DONE).count();
    }

    /**
     * 是否全部完成；空计划返回 false。
     *
     * @return true 表示至少一条且全部完成
     */
    public boolean allDone() {
        return !items.isEmpty() && completedCount() == items.size();
    }

    /**
     * 当前应关注的待办：优先进行中，其次第一条未完成。
     *
     * @return 下标；没有待办或全部完成时为空
     */
    public OptionalInt currentIndex() {
        for (int index = 0; index < items.size(); index++) {
            if (items.get(index).status() == PlanItemStatus.IN_PROGRESS) {
                return OptionalInt.of(index);
            }
        }
        for (int index = 0; index < items.size(); index++) {
            if (items.get(index).status() == PlanItemStatus.PENDING) {
                return OptionalInt.of(index);
            }
        }
        return OptionalInt.empty();
    }
}
