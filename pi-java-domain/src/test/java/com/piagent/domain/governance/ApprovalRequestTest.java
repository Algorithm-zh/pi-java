package com.piagent.domain.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.domain.agent.RunId;
import com.piagent.domain.governance.model.ApprovalArguments;
import com.piagent.domain.governance.model.ApprovalContext;
import com.piagent.domain.governance.model.ApprovalDecision;
import com.piagent.domain.governance.model.ApprovalId;
import com.piagent.domain.governance.model.ApprovalRequest;
import com.piagent.domain.governance.model.ApprovalStatus;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.shared.IdGenerator;
import com.piagent.domain.shared.Ulid;
import com.piagent.domain.tool.model.ToolCallId;
import com.piagent.domain.tool.model.ToolName;
import com.piagent.domain.tool.model.ToolRisk;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 审批聚合状态机测试。
 *
 * <p>重点覆盖唯一合法迁移路径、终态不可逆、系统过期与取消的决策者，
 * 以及参数摘要和状态字段的不变量。</p>
 */
class ApprovalRequestTest {

    private static final Instant NOW = Instant.parse("2026-10-22T08:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(30);

    @Test
    void pendingStartsWithoutDecisionAndExpiresAfterTtl() {
        ApprovalContext context = context();
        ApprovalRequest request = ApprovalRequest.pending(ApprovalId.newId(SEQ), context, NOW, TTL);

        assertThat(request.status()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(request.isPending()).isTrue();
        assertThat(request.decidedBy()).isNull();
        assertThat(request.decidedAt()).isNull();
        assertThat(request.reason()).isNull();
        assertThat(request.expiresAt()).isEqualTo(NOW.plus(TTL));
        assertThat(request.createdAt()).isEqualTo(NOW);
        assertThat(request.context()).isEqualTo(context);
        assertThat(request.sessionId()).isEqualTo(context.sessionId());
        assertThat(request.runId()).isEqualTo(context.runId());
        assertThat(request.toolCallId().toString()).isEqualTo("call-1");
        assertThat(request.toolName().toString()).isEqualTo("write");
        assertThat(request.riskLevel()).isEqualTo(ToolRisk.HIGH);
    }

    @Test
    void approveAndDenyMoveToTerminalState() {
        ApprovalRequest approved = pending().approve(" user ", "  可以  ", NOW.plusSeconds(5));
        ApprovalRequest denied = pending().deny("user", null, NOW.plusSeconds(6));

        assertThat(approved.status()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(approved.decidedBy()).isEqualTo("user");
        assertThat(approved.reason()).isEqualTo("可以");
        assertThat(approved.decidedAt()).isEqualTo(NOW.plusSeconds(5));
        assertThat(denied.status()).isEqualTo(ApprovalStatus.DENIED);
        assertThat(denied.reason()).isNull();
    }

    @Test
    void applyDispatchesUserDecision() {
        assertThat(pending().apply(ApprovalDecision.APPROVE, "user", null, NOW)
                .status()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(pending().apply(ApprovalDecision.DENY, "user", "不要", NOW)
                .status()).isEqualTo(ApprovalStatus.DENIED);
        assertThat(ApprovalDecision.APPROVE.targetStatus())
                .isEqualTo(ApprovalStatus.APPROVED);
    }

    @Test
    void terminalStateCannotTransitionAgain() {
        ApprovalRequest approved = pending().approve("user", null, NOW.plusSeconds(1));

        assertThatThrownBy(() -> approved.deny("user", null, NOW.plusSeconds(2)))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.APPROVAL_INVALID_STATE));
        assertThatThrownBy(() -> approved.expire(NOW.plusSeconds(3)))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.APPROVAL_INVALID_STATE));
        assertThatThrownBy(() -> approved.cancel("取消", NOW.plusSeconds(3)))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.APPROVAL_INVALID_STATE));
    }

    @Test
    void expireAndCancelUseSystemDecider() {
        ApprovalRequest expired = pending().expire(NOW.plus(TTL));
        ApprovalRequest cancelled = pending().cancel("  ", NOW.plusSeconds(10));

        assertThat(expired.status()).isEqualTo(ApprovalStatus.EXPIRED);
        assertThat(expired.decidedBy()).isEqualTo(ApprovalRequest.SYSTEM_DECIDER);
        assertThat(expired.reason()).contains("超时");
        assertThat(cancelled.status()).isEqualTo(ApprovalStatus.CANCELLED);
        assertThat(cancelled.decidedBy()).isEqualTo(ApprovalRequest.SYSTEM_DECIDER);
        assertThat(cancelled.reason()).isEqualTo("运行已取消");
    }

    @Test
    void expiryBoundaryIsInclusive() {
        ApprovalRequest request = pending();

        assertThat(request.isExpiredAt(NOW.plus(TTL).minusMillis(1))).isFalse();
        assertThat(request.isExpiredAt(NOW.plus(TTL))).isTrue();
        assertThat(request.approve("user", null, NOW).isExpiredAt(NOW.plus(TTL))).isFalse();
    }

    @Test
    void rejectsInvalidTtlAndTimestamps() {
        assertThatThrownBy(() -> ApprovalRequest.pending(ApprovalId.newId(SEQ), context(), NOW,
                Duration.ZERO)).isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.INVALID_ARGUMENT));
        assertThatThrownBy(() -> ApprovalRequest.pending(ApprovalId.newId(SEQ), context(), NOW,
                Duration.ofHours(25))).isInstanceOf(DomainException.class)
                .hasMessageContaining("24 小时");
        assertThatThrownBy(() -> new ApprovalRequest(ApprovalId.newId(SEQ), context(),
                ApprovalStatus.PENDING, null, null, null, NOW, NOW))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("晚于创建时间");
    }

    @Test
    void pendingCannotCarryDecisionAndTerminalMustCarryDecision() {
        ApprovalId id = ApprovalId.newId(SEQ);
        assertThatThrownBy(() -> new ApprovalRequest(id, context(), ApprovalStatus.PENDING,
                null, null, NOW, NOW.plus(TTL), NOW))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("PENDING");
        assertThatThrownBy(() -> new ApprovalRequest(id, context(), ApprovalStatus.APPROVED,
                null, null, NOW.plusSeconds(1), NOW.plus(TTL), NOW))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("决策者");
        assertThatThrownBy(() -> new ApprovalRequest(id, context(), ApprovalStatus.APPROVED,
                null, "user", null, NOW.plus(TTL), NOW))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("决策时间");
    }

    @Test
    void limitsDecisionFields() {
        assertThatThrownBy(() -> pending().approve("u".repeat(65), null, NOW))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("决策者");
        assertThatThrownBy(() -> pending().deny("user",
                "x".repeat(ApprovalRequest.MAX_REASON_LENGTH + 1), NOW))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("审批意见");
    }

    @Test
    void argumentsRequireLowercaseHexDigest() {
        assertThatThrownBy(() -> new ApprovalArguments("ABC", Map.of()))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("64 位");
        assertThat(new ApprovalArguments("a".repeat(64), Map.of("path", "a.txt")).redacted())
                .containsEntry("path", "a.txt");
    }

    private static ApprovalRequest pending() {
        return ApprovalRequest.pending(ApprovalId.newId(SEQ), context(), NOW, TTL);
    }

    private static ApprovalContext context() {
        return new ApprovalContext(new SessionId(Ulid.generate(NOW)),
                new RunId(Ulid.generate(NOW)), new ToolCallId("call-1"), new ToolName("write"),
                new ApprovalArguments("b".repeat(64), Map.of("path", "a.txt", "token", "***")),
                ToolRisk.HIGH);
    }

    private static final class SeqGenerator implements IdGenerator {

        private long next = 1;

        @Override
        public String newId() {
            long value = next++;
            return "01J" + String.format("%023d", value);
        }
    }

    private static final SeqGenerator SEQ = new SeqGenerator();
}
