package com.piagent.app.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.app.PiJavaApplication;
import com.piagent.domain.agent.RunId;
import com.piagent.domain.governance.model.ApprovalArguments;
import com.piagent.domain.governance.model.ApprovalContext;
import com.piagent.domain.governance.model.ApprovalId;
import com.piagent.domain.governance.model.ApprovalRequest;
import com.piagent.domain.governance.model.ApprovalStatus;
import com.piagent.domain.governance.port.ApprovalRepository;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.SessionConfig;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.port.SessionRepository;
import com.piagent.domain.shared.Ulid;
import com.piagent.domain.tool.model.ToolCallId;
import com.piagent.domain.tool.model.ToolName;
import com.piagent.domain.tool.model.ToolRisk;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * H2 + Flyway + MyBatis 的审批持久化集成测试。
 *
 * <p>覆盖参数 JSON 往返、按会话/状态查询、pending CAS、唯一键与级联删除。</p>
 */
@SpringBootTest(classes = PiJavaApplication.class)
@ActiveProfiles("test")
class ApprovalPersistenceIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-22T08:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(30);

    @Autowired
    private ApprovalRepository approvals;

    @Autowired
    private SessionRepository sessions;

    @Autowired
    private JdbcTemplate jdbc;

    private SessionId sessionId;

    private RunId runId;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM pi_approval_request");
        jdbc.update("DELETE FROM pi_session_entry");
        jdbc.update("DELETE FROM pi_session");
        ChatSession session = ChatSession.createRoot(newSessionId(), "审批测试",
                SessionConfig.forWorkspace(Path.of("target/approval-ws").toAbsolutePath(), "fake"));
        sessions.insert(session);
        sessionId = session.identity().id();
        runId = new RunId(Ulid.generate(NOW));
    }

    @Test
    void roundTripPreservesRedactedArgumentsAndContext() {
        ApprovalRequest request = pending("call-1", "write");
        approvals.insert(request);

        ApprovalRequest loaded = approvals.findById(request.id()).orElseThrow();

        assertThat(loaded).isEqualTo(request);
        assertThat(loaded.context().arguments().redacted())
                .containsEntry("path", "notes.txt")
                .containsEntry("apiKey", "***");
        assertThat(loaded.status()).isEqualTo(ApprovalStatus.PENDING);
    }

    @Test
    void queryBySessionFiltersStatus() {
        ApprovalRequest pending = pending("call-1", "write");
        ApprovalRequest denied = pending("call-2", "edit")
                .deny("user", "不需要", NOW.plusSeconds(3));
        approvals.insert(pending);
        approvals.insert(denied);

        assertThat(approvals.findBySession(sessionId, null)).hasSize(2);
        assertThat(approvals.findBySession(sessionId, ApprovalStatus.PENDING))
                .extracting(item -> item.id().toString())
                .containsExactly(pending.id().toString());
        assertThat(approvals.findBySession(sessionId, ApprovalStatus.DENIED))
                .extracting(item -> item.toolName().toString())
                .containsExactly("edit");
        assertThat(approvals.findByRun(pending.runId())).hasSize(2);
    }

    @Test
    void updateIfPendingIsCompareAndSet() {
        ApprovalRequest request = pending("call-1", "write");
        approvals.insert(request);
        ApprovalRequest approved = request.approve("user", "允许一次", NOW.plusSeconds(2));

        assertThat(approvals.updateIfPending(approved)).isTrue();
        assertThat(approvals.updateIfPending(request.deny("user", null, NOW.plusSeconds(4))))
                .isFalse();
        ApprovalRequest loaded = approvals.findById(request.id()).orElseThrow();
        assertThat(loaded.status()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(loaded.reason()).isEqualTo("允许一次");
        assertThat(loaded.decidedBy()).isEqualTo("user");
    }

    @Test
    void duplicateRunAndCallIsRejected() {
        ApprovalRequest first = pending("call-dup", "write");
        approvals.insert(first);

        assertThatThrownBy(() -> approvals.insert(pending("call-dup", "edit")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void expireAllPendingOnlyTouchesPendingRows() {
        approvals.insert(pending("call-1", "write"));
        approvals.insert(pending("call-2", "write").deny("user", null, NOW.plusSeconds(1)));

        int expired = approvals.expireAllPending(NOW.plus(Duration.ofMinutes(1)));

        assertThat(expired).isEqualTo(1);
        assertThat(approvals.findBySession(sessionId, ApprovalStatus.EXPIRED)).hasSize(1);
        assertThat(approvals.findBySession(sessionId, ApprovalStatus.DENIED)).hasSize(1);
    }

    @Test
    void deletingSessionCascadesApprovals() {
        approvals.insert(pending("call-1", "write"));

        jdbc.update("DELETE FROM pi_session WHERE id = ?", sessionId.toString());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pi_approval_request", Integer.class))
                .isZero();
    }

    private ApprovalRequest pending(String callId, String toolName) {
        ApprovalContext context = new ApprovalContext(sessionId, runId,
                new ToolCallId(callId), new ToolName(toolName),
                new ApprovalArguments("c".repeat(64),
                        Map.of("path", "notes.txt", "apiKey", "***")),
                ToolRisk.HIGH);
        return ApprovalRequest.pending(new ApprovalId(Ulid.generate(NOW)), context, NOW, TTL);
    }

    private static SessionId newSessionId() {
        return new SessionId(Ulid.generate(NOW));
    }
}
