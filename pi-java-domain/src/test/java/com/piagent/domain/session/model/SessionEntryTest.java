package com.piagent.domain.session.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.domain.session.SessionFixtures;
import com.piagent.domain.session.model.payload.LabelPayload;
import com.piagent.domain.session.model.payload.MessagePayload;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SessionEntryTest {

    private final SessionId sessionId = SessionFixtures.sessionId(1);

    @Test
    void createsValidRootEntry() {
        SessionEntry entry = new SessionEntry(SessionFixtures.entryId(999), sessionId, 1, null,
                EntryType.MESSAGE, SessionEntry.CURRENT_PAYLOAD_VERSION,
                new MessagePayload(SessionFixtures.user("hello")), 10, SessionFixtures.NOW);

        assertThat(entry.seq()).isEqualTo(1);
        assertThat(entry.parentId()).isNull();
        assertThat(entry.type()).isEqualTo(EntryType.MESSAGE);
        assertThat(entry.payloadVersion()).isEqualTo(SessionEntry.CURRENT_PAYLOAD_VERSION);
        assertThat(entry.isMessage()).isTrue();
        assertThat(entry.isToolResult()).isFalse();
    }

    @Test
    void rejectsPayloadTypeMismatch() {
        assertThatThrownBy(() -> new SessionEntry(SessionFixtures.entryId(1), sessionId, 1, null,
                EntryType.COMPACTION, 1, new MessagePayload(SessionFixtures.user("hello")), 10,
                SessionFixtures.NOW))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.ENTRY_TYPE_MISMATCH));
    }

    @Test
    void enforcesParentRules() {
        assertThatThrownBy(() -> new SessionEntry(SessionFixtures.entryId(1), sessionId, 1,
                SessionFixtures.entryId(2), EntryType.MESSAGE, 1,
                new MessagePayload(SessionFixtures.user("hello")), 10, SessionFixtures.NOW))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.INVALID_ENTRY));

        assertThatThrownBy(() -> new SessionEntry(SessionFixtures.entryId(1), sessionId, 2, null,
                EntryType.MESSAGE, 1, new MessagePayload(SessionFixtures.user("hello")), 10,
                SessionFixtures.NOW))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.INVALID_ENTRY));
    }

    @Test
    void rejectsInvalidVersionAndTokens() {
        assertThatThrownBy(() -> new SessionEntry(SessionFixtures.entryId(1), sessionId, 1, null,
                EntryType.LABEL, 0, new LabelPayload(SessionFixtures.entryId(2), "x"), 0,
                SessionFixtures.NOW))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.UNSUPPORTED_PAYLOAD));

        assertThatThrownBy(() -> new SessionEntry(SessionFixtures.entryId(1), sessionId, 1, null,
                EntryType.LABEL, 1, new LabelPayload(SessionFixtures.entryId(2), "x"), -1,
                Instant.now()))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.INVALID_ENTRY));
    }

    @Test
    void detectsToolResultEntry() {
        SessionEntry entry = SessionFixtures.message(sessionId, 2, SessionFixtures.entryId(101),
                SessionFixtures.toolResult("call-1", "read"));

        assertThat(entry.isToolResult()).isTrue();
        assertThat(entry.isMessage()).isTrue();
    }
}
