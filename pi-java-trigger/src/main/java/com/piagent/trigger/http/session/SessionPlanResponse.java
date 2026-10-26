package com.piagent.trigger.http.session;

import com.piagent.application.plan.SessionPlanSnapshot;
import com.piagent.domain.plan.model.PlanItem;
import com.piagent.domain.session.model.payload.VerificationPayload;
import java.util.List;

/**
 * 会话计划响应。
 *
 * @param hasPlan 是否存在待办
 * @param total 总条数
 * @param completed 已完成条数
 * @param items 待办列表
 * @param verification 最近一次自动验证；从未验证时为 null
 */
public record SessionPlanResponse(
        boolean hasPlan,
        int total,
        long completed,
        List<Item> items,
        Verification verification) {

    /**
     * 单条待办。
     *
     * @param content 内容
     * @param status pending / in_progress / done
     */
    public record Item(String content, String status) {
    }

    /**
     * 验证结果。
     *
     * @param command 命令
     * @param passed 是否通过
     * @param exitCode 退出码；超时或无法启动时为 null
     * @param timedOut 是否超时
     * @param truncated 输出是否截断
     * @param durationMs 耗时
     * @param output 输出
     */
    public record Verification(String command, boolean passed, Integer exitCode,
            boolean timedOut, boolean truncated, long durationMs, String output) {
    }

    /**
     * 从应用层快照生成响应。
     *
     * @param snapshot 快照
     * @return 响应
     */
    public static SessionPlanResponse from(SessionPlanSnapshot snapshot) {
        return new SessionPlanResponse(snapshot.hasPlan(), snapshot.plan().items().size(),
                snapshot.completed(),
                snapshot.plan().items().stream().map(SessionPlanResponse::item).toList(),
                snapshot.verification() == null ? null : verification(snapshot.verification()));
    }

    private static Item item(PlanItem item) {
        return new Item(item.content(), item.status().wireName());
    }

    private static Verification verification(VerificationPayload payload) {
        return new Verification(payload.command(), payload.passed(), payload.exitCode(),
                payload.timedOut(), payload.truncated(), payload.durationMs(), payload.output());
    }
}
