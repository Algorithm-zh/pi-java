package com.piagent.domain.session.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.domain.session.SessionFixtures;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ChatSessionTest {

    private static final Path CWD = Path.of(".").toAbsolutePath().normalize();

    private final SessionId sessionId = SessionFixtures.sessionId(1);

    @Test
    void createsRootSession() {
        ChatSession session = ChatSession.createRoot(sessionId, "demo", config());

        assertThat(session.identity().origin()).isEqualTo(Origin.USER);
        assertThat(session.identity().depth()).isZero();
        assertThat(session.identity().rootSessionId()).isEqualTo(sessionId);
        assertThat(session.status()).isEqualTo(SessionStatus.IDLE);
        assertThat(session.version()).isEqualTo(ChatSession.INITIAL_VERSION);
        assertThat(session.isEmpty()).isTrue();
    }

    @Test
    void movesThroughRunStateMachine() {
        ChatSession session = ChatSession.createRoot(sessionId, "demo", config());

        ChatSession running = session.markRunning();
        assertThat(running.status()).isEqualTo(SessionStatus.RUNNING);

        ChatSession waiting = running.markWaitingApproval();
        assertThat(waiting.status()).isEqualTo(SessionStatus.WAITING_APPROVAL);

        assertThat(waiting.markRunning().status()).isEqualTo(SessionStatus.RUNNING);
        assertThat(waiting.markIdle().status()).isEqualTo(SessionStatus.IDLE);
    }

    @Test
    void rejectsIllegalStatusTransitions() {
        ChatSession running = ChatSession.createRoot(sessionId, "demo", config()).markRunning();
        ChatSession archived = ChatSession.createRoot(sessionId, "demo", config()).archive();

        assertThatThrownBy(running::markRunning)
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.INVALID_STATE_TRANSITION));
        assertThatThrownBy(archived::markRunning)
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.INVALID_STATE_TRANSITION));
    }

    @Test
    void movesLeafOnlyWhenIdle() {
        ChatSession idle = ChatSession.createRoot(sessionId, "demo", config());
        EntryId target = SessionFixtures.entryId(5);

        assertThat(idle.moveLeaf(target).leafEntryId()).isEqualTo(target);
        assertThatThrownBy(() -> idle.markRunning().moveLeaf(target))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.INVALID_STATE_TRANSITION));
        assertThatThrownBy(() -> idle.moveLeaf(target).moveLeaf(target))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.INVALID_BRANCH));
    }

    @Test
    void preventsBranchMoveOnArchivedSession() {
        ChatSession archived = ChatSession.createRoot(sessionId, "demo", config()).archive();

        assertThatThrownBy(() -> archived.moveLeaf(SessionFixtures.entryId(2)))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.SESSION_ARCHIVED));
    }

    @Test
    void changesModelOnlyWhenIdle() {
        ChatSession idle = ChatSession.createRoot(sessionId, "demo", config());
        ChatSession changed = idle.changeModel("deepseek", ThinkingLevel.HIGH);

        assertThat(changed.config().modelProfileId()).isEqualTo("deepseek");
        assertThat(changed.config().thinkingLevel()).isEqualTo(ThinkingLevel.HIGH);
        assertThatThrownBy(() -> idle.markRunning().changeModel("deepseek", ThinkingLevel.HIGH))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.INVALID_STATE_TRANSITION));
    }

    @Test
    void createsForkChild() {
        ChatSession parent = ChatSession.createRoot(sessionId, "demo", config());
        EntryId forkPoint = SessionFixtures.entryId(3);

        ChatSession child = ChatSession.createChild(SessionFixtures.sessionId(2), parent, forkPoint,
                Origin.FORK, "branch", config());

        assertThat(child.identity().origin()).isEqualTo(Origin.FORK);
        assertThat(child.identity().depth()).isEqualTo(1);
        assertThat(child.identity().parentSessionId()).isEqualTo(sessionId);
        assertThat(child.identity().rootSessionId()).isEqualTo(sessionId);
        assertThat(child.identity().forkFromEntryId()).isEqualTo(forkPoint);
        assertThat(child.version()).isEqualTo(ChatSession.INITIAL_VERSION);
    }

    @Test
    void createsSubagentWithoutForkPoint() {
        ChatSession parent = ChatSession.createRoot(sessionId, "demo", config());
        ChatSession child = ChatSession.createChild(SessionFixtures.sessionId(2), parent, null,
                Origin.SUBAGENT, "worker", config());

        assertThat(child.identity().origin()).isEqualTo(Origin.SUBAGENT);
        assertThat(child.identity().forkFromEntryId()).isNull();
    }

    @Test
    void rejectsInvalidChildCreation() {
        ChatSession parent = ChatSession.createRoot(sessionId, "demo", config());
        SessionId childId = SessionFixtures.sessionId(2);

        assertThatThrownBy(() -> ChatSession.createChild(childId, parent,
                SessionFixtures.entryId(3), Origin.USER, "x", config()))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.INVALID_ARGUMENT));
        assertThatThrownBy(() -> ChatSession.createChild(childId, parent, null, Origin.FORK, "x", config()))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.INVALID_ARGUMENT));
        assertThatThrownBy(() -> ChatSession.createChild(childId, parent.archive(), null,
                Origin.SUBAGENT, "x", config()))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.SESSION_ARCHIVED));
    }

    @Test
    void versionNeverGoesBackwards() {
        ChatSession session = ChatSession.createRoot(sessionId, "demo", config()).withVersion(5);

        assertThat(session.version()).isEqualTo(5);
        assertThatThrownBy(() -> session.withVersion(4))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.INVALID_ARGUMENT));
    }

    private SessionConfig config() {
        return SessionConfig.forWorkspace(CWD, "fake-model");
    }
}
