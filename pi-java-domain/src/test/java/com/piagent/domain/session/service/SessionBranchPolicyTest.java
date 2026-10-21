package com.piagent.domain.session.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.domain.session.SessionFixtures;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.util.List;
import org.junit.jupiter.api.Test;

class SessionBranchPolicyTest {

    private final SessionBranchPolicy policy = new SessionBranchPolicy();
    private final SessionId sessionId = SessionFixtures.sessionId(1);

    @Test
    void acceptsMessageEntry() {
        SessionEntry entry = SessionFixtures.message(sessionId, 1, null, SessionFixtures.user("hi"));

        SessionEntry target = policy.requireBranchTarget(sessionId, List.of(entry), entry.id());

        assertThat(target).isEqualTo(entry);
    }

    @Test
    void rejectsToolResultTarget() {
        SessionEntry call = SessionFixtures.message(sessionId, 1, null,
                SessionFixtures.assistantWithToolCall("call-1", "read"));
        SessionEntry result = SessionFixtures.message(sessionId, 2, call.id(),
                SessionFixtures.toolResult("call-1", "read"));

        assertThatThrownBy(() -> policy.requireBranchTarget(sessionId, List.of(call, result), result.id()))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.ENTRY_NOT_BRANCHABLE));
    }

    @Test
    void rejectsForeignSessionAndMissingTarget() {
        SessionEntry entry = SessionFixtures.message(sessionId, 1, null, SessionFixtures.user("hi"));

        assertThatThrownBy(() -> policy.requireBranchTarget(SessionFixtures.sessionId(2), List.of(entry), entry.id()))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.INVALID_BRANCH));
        assertThatThrownBy(() -> policy.requireBranchTarget(sessionId, List.of(entry), SessionFixtures.entryId(999)))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.ENTRY_NOT_FOUND));
    }
}
