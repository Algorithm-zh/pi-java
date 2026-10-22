package com.piagent.application.governance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.application.agent.service.RunEventHub;
import com.piagent.application.agent.service.SessionEntryAppender;
import com.piagent.application.testsupport.InMemoryApprovalRepository;
import com.piagent.application.testsupport.InMemorySessionEntryStore;
import com.piagent.application.testsupport.InMemorySessionRepository;
import com.piagent.application.testsupport.SequentialIdGenerator;
import com.piagent.domain.agent.RunId;
import com.piagent.domain.governance.model.ApprovalContext;
import com.piagent.domain.governance.model.ApprovalDecision;
import com.piagent.domain.governance.model.ApprovalRequest;
import com.piagent.domain.governance.model.ApprovalStatus;
import com.piagent.domain.governance.service.ApprovalArgumentsSanitizer;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.EntryType;
import com.piagent.domain.session.model.SessionConfig;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.SessionStatus;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.tool.model.ToolCallId;
import com.piagent.domain.tool.model.ToolName;
import com.piagent.domain.tool.model.ToolRisk;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 审批创建、等待唤醒、超时、取消与重复决策测试。
 *
 * <p>使用内存仓储与真实 entry 追加器，不启动 Spring；HTTP 决策在另一个线程
 * 发生，验证 future 唤醒与 CAS 冲突路径。</p>
 */
class ApprovalGateServiceTest {

    private static final Path WORKSPACE = Path.of("target/approval-gate-unit").toAbsolutePath();
    private static final String MODEL = "gate-fake";

    private final InMemoryApprovalRepository approvals = new InMemoryApprovalRepository();
    private final InMemorySessionRepository sessions = new InMemorySessionRepository();
    private final InMemorySessionEntryStore entries = new InMemorySessionEntryStore();
    private final SequentialIdGenerator ids = new SequentialIdGenerator();
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final Clock clock = Clock.systemUTC();

    private ApprovalApplicationService service;
    private ApprovalGateService gate;

    @BeforeEach
    void setUp() {
        SessionEntryAppender appender = new SessionEntryAppender(sessions, entries, ids, clock);
        service = new ApprovalApplicationService(approvals, appender, new ApprovalWaitRegistry(),
                new ApprovalEventPublisher(new RunEventHub(clock)), ids, clock);
        gate = new ApprovalGateService(service, Duration.ofSeconds(5), clock);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    void decisionWakesWaitingGateAndWritesAuditEntry() throws Exception {
        ChatSession session = runningSession();
        CompletableFuture<ApprovalRequest> waiting = CompletableFuture.supplyAsync(
                () -> gate.await(context(session.identity().id()), () -> false), pool);
        ApprovalRequest pending = awaitPending(session.identity().id());

        service.decide(pending.id(), ApprovalDecision.APPROVE, "user", "允许一次");
        ApprovalRequest result = waiting.get(2, TimeUnit.SECONDS);

        assertThat(result.status()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(result.reason()).isEqualTo("允许一次");
        assertThat(statusOf(session.identity().id())).isEqualTo(SessionStatus.RUNNING);
        assertThat(lastEntryType(session.identity().id())).isEqualTo(EntryType.APPROVAL);
    }

    @Test
    void ttlElapsedMarksApprovalExpired() {
        ChatSession session = runningSession();
        ApprovalGateService shortGate = new ApprovalGateService(service,
                Duration.ofMillis(50), clock);

        ApprovalRequest result = shortGate.await(context(session.identity().id()), () -> false);

        assertThat(result.status()).isEqualTo(ApprovalStatus.EXPIRED);
        assertThat(result.decidedBy()).isEqualTo(ApprovalRequest.SYSTEM_DECIDER);
        assertThat(statusOf(session.identity().id())).isEqualTo(SessionStatus.RUNNING);
        assertThat(lastEntryType(session.identity().id())).isEqualTo(EntryType.APPROVAL);
    }

    @Test
    void cancelSignalMarksApprovalCancelled() throws Exception {
        ChatSession session = runningSession();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        CompletableFuture<ApprovalRequest> waiting = CompletableFuture.supplyAsync(
                () -> gate.await(context(session.identity().id()), cancelled::get), pool);
        awaitPending(session.identity().id());

        cancelled.set(true);
        ApprovalRequest result = waiting.get(2, TimeUnit.SECONDS);

        assertThat(result.status()).isEqualTo(ApprovalStatus.CANCELLED);
        assertThat(statusOf(session.identity().id())).isEqualTo(SessionStatus.RUNNING);
    }

    @Test
    void repeatedDecisionConflictsWithInvalidState() {
        ChatSession session = runningSession();
        ApprovalRequest pending = service.open(context(session.identity().id()),
                Duration.ofMinutes(1)).request();

        service.decide(pending.id(), ApprovalDecision.APPROVE, "user", null);

        assertThatThrownBy(() -> service.decide(pending.id(), ApprovalDecision.DENY, "user", null))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.APPROVAL_INVALID_STATE));
    }

    private ChatSession runningSession() {
        ChatSession session = ChatSession.createRoot(SessionId.newId(ids), "审批等待测试",
                SessionConfig.forWorkspace(WORKSPACE, MODEL));
        sessions.insert(session);
        ChatSession running = session.markRunning();
        sessions.update(running);
        return running;
    }

    private ApprovalContext context(SessionId sessionId) {
        return new ApprovalContext(sessionId, RunId.newId(ids), new ToolCallId("call-1"),
                new ToolName("write"), ApprovalArgumentsSanitizer.sanitize(
                        Map.of("path", "notes.txt")), ToolRisk.HIGH);
    }

    private ApprovalRequest awaitPending(SessionId sessionId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            List<ApprovalRequest> pending = approvals.findBySession(sessionId,
                    ApprovalStatus.PENDING);
            if (!pending.isEmpty()) {
                return pending.getFirst();
            }
            Thread.sleep(10);
        }
        throw new AssertionError("2 秒内没有创建审批待办");
    }

    private SessionStatus statusOf(SessionId sessionId) {
        return sessions.findById(sessionId).orElseThrow().status();
    }

    private EntryType lastEntryType(SessionId sessionId) {
        ChatSession session = sessions.findById(sessionId).orElseThrow();
        return entries.loadBranch(sessionId, session.leafEntryId()).getLast().type();
    }
}
