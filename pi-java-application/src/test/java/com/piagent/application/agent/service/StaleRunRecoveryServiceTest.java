package com.piagent.application.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.piagent.application.governance.service.ApprovalApplicationService;
import com.piagent.domain.agent.AgentRun;
import com.piagent.domain.agent.RunBudget;
import com.piagent.domain.agent.RunId;
import com.piagent.domain.governance.model.ApprovalId;
import com.piagent.domain.governance.model.ApprovalRequest;
import com.piagent.domain.governance.model.ApprovalStatus;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.SessionConfig;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.SessionIdentity;
import com.piagent.domain.session.model.SessionStatus;
import com.piagent.domain.session.port.SessionRepository;
import com.piagent.domain.shared.Ulid;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 启动自愈的单元测试：内存中没有活跃 run 的运行态会话必须被复位。
 */
class StaleRunRecoveryServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final SessionId SESSION_ID = new SessionId(Ulid.generate(NOW));

    private SessionRepository sessions;
    private RunRuntime runtime;
    private ApprovalApplicationService approvals;
    private InterruptedToolCallCloser closer;
    private StaleRunRecoveryService service;

    @BeforeEach
    void setUp() {
        sessions = mock(SessionRepository.class);
        runtime = mock(RunRuntime.class);
        approvals = mock(ApprovalApplicationService.class);
        closer = mock(InterruptedToolCallCloser.class);
        service = new StaleRunRecoveryService(sessions, runtime, approvals, closer);
        when(sessions.findRecent(500)).thenReturn(List.of());
        when(closer.closeDangling(any())).thenReturn(0);
    }

    @Test
    void resetsStuckSessionWithoutActiveRun() {
        ApprovalRequest pending = mock(ApprovalRequest.class);
        ApprovalId approvalId = ApprovalId.parse(Ulid.generate(NOW).value());
        when(pending.id()).thenReturn(approvalId);
        when(sessions.findStuck(500)).thenReturn(List.of(runningSession()));
        when(runtime.findActiveBySession(SESSION_ID)).thenReturn(Optional.empty());
        when(approvals.findBySession(SESSION_ID, ApprovalStatus.PENDING))
                .thenReturn(List.of(pending));
        when(closer.closeDangling(SESSION_ID)).thenReturn(1);

        assertThat(service.recover()).isEqualTo(1);
        verify(approvals).cancel(approvalId, "server-restart");
        verify(closer).closeDangling(SESSION_ID);
        verify(runtime).forceIdle(SESSION_ID);
    }

    @Test
    void skipsSessionWithActiveRunInThisProcess() {
        AgentRun run = AgentRun.start(RunId.newId(() -> Ulid.generate(NOW).value()),
                SESSION_ID, RunBudget.defaults(), NOW);
        when(sessions.findStuck(500)).thenReturn(List.of(runningSession()));
        when(runtime.findActiveBySession(SESSION_ID)).thenReturn(Optional.of(run));

        assertThat(service.recover()).isZero();
        verify(approvals, never()).cancel(any(), any());
        verify(closer, never()).closeDangling(any());
        verify(runtime, never()).forceIdle(SESSION_ID);
    }

    @Test
    void repairsDanglingCallsOnIdleSession() {
        ChatSession idle = ChatSession.restore(SessionIdentity.root(SESSION_ID), "空闲会话",
                SessionConfig.forWorkspace(Path.of("D:/demo"), "fake-demo"),
                SessionStatus.IDLE, null, ChatSession.INITIAL_VERSION);
        when(sessions.findRecent(500)).thenReturn(List.of(idle));
        when(runtime.findActiveBySession(SESSION_ID)).thenReturn(Optional.empty());
        when(closer.closeDangling(SESSION_ID)).thenReturn(1);

        assertThat(service.recover()).isEqualTo(1);
        verify(closer).closeDangling(SESSION_ID);
        verify(runtime, never()).forceIdle(SESSION_ID);
    }

    private static ChatSession runningSession() {
        return ChatSession.restore(SessionIdentity.root(SESSION_ID), "运行中的会话",
                SessionConfig.forWorkspace(Path.of("D:/demo"), "fake-demo"),
                SessionStatus.RUNNING, null, ChatSession.INITIAL_VERSION);
    }
}
