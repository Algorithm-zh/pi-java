package com.piagent.domain.governance.model;

import com.piagent.domain.agent.RunId;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import com.piagent.domain.tool.model.ToolCallId;
import com.piagent.domain.tool.model.ToolName;
import com.piagent.domain.tool.model.ToolRisk;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 审批请求聚合根。
 *
 * <p>审批是“待办投影 + 决策状态机”，不是工具执行本身。工具协调器在策略解析为
 * {@code ASK} 时创建待办并等待；用户通过 HTTP 决策；run 取消或待办过期由系统
 * 写入终态。任何终态都必须同步追加一条 {@code APPROVAL} 会话 entry，审计事实
 * 以会话日志为准，本聚合可以被清理。</p>
 *
 * <p>状态迁移只有一条路径：{@link ApprovalStatus#PENDING} → 任一终态。
 * 终态之间不可回退，重复决策由应用层转成 {@code APPROVAL_INVALID_STATE}。</p>
 *
 * @param id 审批标识
 * @param context 被审批的工具调用
 * @param status 当前状态
 * @param reason 决策意见，可为 null
 * @param decidedBy 决策者；系统决策为 {@value #SYSTEM_DECIDER}
 * @param decidedAt 决策时间；PENDING 时为 null
 * @param expiresAt 过期时间
 * @param createdAt 创建时间
 */
public record ApprovalRequest(ApprovalId id, ApprovalContext context, ApprovalStatus status,
        String reason, String decidedBy, Instant decidedAt, Instant expiresAt, Instant createdAt) {

    /** 系统写入过期/取消时的决策者。 */
    public static final String SYSTEM_DECIDER = "system";

    /** 决策意见最大长度。 */
    public static final int MAX_REASON_LENGTH = 1_024;

    /** 决策者最大长度。 */
    public static final int MAX_DECIDER_LENGTH = 64;

    /** 审批有效期上限，避免待办长期悬挂。 */
    public static final Duration MAX_TTL = Duration.ofHours(24);

    /**
     * 校验审批状态不变量。
     *
     * @param id 审批标识
     * @param context 审批对象
     * @param status 状态
     * @param reason 意见
     * @param decidedBy 决策者
     * @param decidedAt 决策时间
     * @param expiresAt 过期时间
     * @param createdAt 创建时间
     */
    public ApprovalRequest {
        Objects.requireNonNull(id, "id 不能为空");
        Objects.requireNonNull(context, "context 不能为空");
        Objects.requireNonNull(status, "status 不能为空");
        Objects.requireNonNull(expiresAt, "expiresAt 不能为空");
        Objects.requireNonNull(createdAt, "createdAt 不能为空");
        Guard.require(expiresAt.isAfter(createdAt), DomainErrorCode.INVALID_ARGUMENT,
                "审批过期时间必须晚于创建时间");
        reason = normalizeReason(reason);
        decidedBy = normalizeDecider(decidedBy);
        if (status.isPending()) {
            Guard.require(decidedBy == null && decidedAt == null, DomainErrorCode.INVALID_ARGUMENT,
                    "PENDING 审批不能带决策信息");
        } else {
            Guard.require(decidedBy != null, DomainErrorCode.INVALID_ARGUMENT,
                    "终态审批必须有决策者");
            Guard.require(decidedAt != null, DomainErrorCode.INVALID_ARGUMENT,
                    "终态审批必须有决策时间");
        }
    }

    /**
     * 创建待审批请求。
     *
     * @param id 审批标识
     * @param context 审批对象
     * @param now 创建时间
     * @param ttl 有效期
     * @return PENDING 聚合
     */
    public static ApprovalRequest pending(ApprovalId id, ApprovalContext context, Instant now,
            Duration ttl) {
        Objects.requireNonNull(now, "now 不能为空");
        Objects.requireNonNull(ttl, "ttl 不能为空");
        Guard.require(!ttl.isZero() && !ttl.isNegative(), DomainErrorCode.INVALID_ARGUMENT,
                "审批有效期必须为正数");
        Guard.require(ttl.compareTo(MAX_TTL) <= 0, DomainErrorCode.INVALID_ARGUMENT,
                "审批有效期不能超过 24 小时");
        return new ApprovalRequest(id, context, ApprovalStatus.PENDING, null, null, null,
                now.plus(ttl), now);
    }

    /**
     * 批准执行。
     *
     * @param decider 决策者
     * @param comment 意见，可为 null
     * @param now 决策时间
     * @return APPROVED 聚合
     */
    public ApprovalRequest approve(String decider, String comment, Instant now) {
        return decided(ApprovalStatus.APPROVED, decider, comment, now);
    }

    /**
     * 拒绝执行。
     *
     * @param decider 决策者
     * @param comment 意见，可为 null
     * @param now 决策时间
     * @return DENIED 聚合
     */
    public ApprovalRequest deny(String decider, String comment, Instant now) {
        return decided(ApprovalStatus.DENIED, decider, comment, now);
    }

    /**
     * 按用户决策迁移，供应用层统一分派。
     *
     * @param decision 决策
     * @param decider 决策者
     * @param comment 意见
     * @param now 决策时间
     * @return 终态聚合
     */
    public ApprovalRequest apply(ApprovalDecision decision, String decider, String comment,
            Instant now) {
        Objects.requireNonNull(decision, "decision 不能为空");
        return decision == ApprovalDecision.APPROVE
                ? approve(decider, comment, now)
                : deny(decider, comment, now);
    }

    /**
     * 标记超时未决策。
     *
     * @param now 过期处理时间
     * @return EXPIRED 聚合
     */
    public ApprovalRequest expire(Instant now) {
        return decided(ApprovalStatus.EXPIRED, SYSTEM_DECIDER, "审批超时未处理", now);
    }

    /**
     * 标记运行取消导致待办失效。
     *
     * @param comment 取消原因
     * @param now 取消时间
     * @return CANCELLED 聚合
     */
    public ApprovalRequest cancel(String comment, Instant now) {
        String value = comment == null || comment.isBlank() ? "运行已取消" : comment;
        return decided(ApprovalStatus.CANCELLED, SYSTEM_DECIDER, value, now);
    }

    /**
     * 是否已经超过有效期但尚未决策。
     *
     * @param now 当前时间
     * @return true 表示应由应用层执行过期迁移
     */
    public boolean isExpiredAt(Instant now) {
        Objects.requireNonNull(now, "now 不能为空");
        return status.isPending() && !now.isBefore(expiresAt);
    }

    /**
     * 是否仍在等待决策。
     *
     * @return true 表示 PENDING
     */
    public boolean isPending() {
        return status.isPending();
    }

    /**
     * 会话标识。
     *
     * @return 会话标识
     */
    public SessionId sessionId() {
        return context.sessionId();
    }

    /**
     * 运行标识。
     *
     * @return 运行标识
     */
    public RunId runId() {
        return context.runId();
    }

    /**
     * 工具调用标识。
     *
     * @return 调用标识
     */
    public ToolCallId toolCallId() {
        return context.toolCallId();
    }

    /**
     * 工具名。
     *
     * @return 工具名
     */
    public ToolName toolName() {
        return context.toolName();
    }

    /**
     * 工具风险级别。
     *
     * @return 风险级别
     */
    public ToolRisk riskLevel() {
        return context.riskLevel();
    }

    private ApprovalRequest decided(ApprovalStatus target, String decider, String comment,
            Instant now) {
        Objects.requireNonNull(target, "target 不能为空");
        Guard.require(target.isTerminal(), DomainErrorCode.INVALID_ARGUMENT, "目标必须是终态");
        Guard.require(status.isPending(), DomainErrorCode.APPROVAL_INVALID_STATE,
                "审批已是终态，不能重复决策: " + status);
        Objects.requireNonNull(now, "now 不能为空");
        return new ApprovalRequest(id, context, target, comment, decider, now, expiresAt, createdAt);
    }

    private static String normalizeReason(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return Guard.maxLength(trimmed, MAX_REASON_LENGTH, DomainErrorCode.INVALID_ARGUMENT,
                "审批意见不能超过 " + MAX_REASON_LENGTH + " 个字符");
    }

    private static String normalizeDecider(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return Guard.maxLength(trimmed, MAX_DECIDER_LENGTH, DomainErrorCode.INVALID_ARGUMENT,
                "决策者不能超过 " + MAX_DECIDER_LENGTH + " 个字符");
    }
}
