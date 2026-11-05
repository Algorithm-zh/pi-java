package com.piagent.trigger.http.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.EntryType;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.payload.BranchSummaryPayload;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * entry 响应测试：回溯简报必须把 details 透给前端。
 */
class SessionEntryResponseTest {

    @Test
    void includesRewindDetailsOnBranchSummary() {
        EntryId from = EntryId.parse("01TST000000000000000000003");
        Map<String, Object> details = Map.of("kind", "rewind", "discardedEntries", 3);
        SessionEntry entry = new SessionEntry(EntryId.parse("01TST000000000000000000004"),
                SessionId.parse("01TST000000000000000000001"), 1, null,
                EntryType.BRANCH_SUMMARY, SessionEntry.CURRENT_PAYLOAD_VERSION,
                new BranchSummaryPayload(from, "回溯简报", details), 8, Instant.now());

        SessionEntryResponse response = SessionEntryResponse.from(entry);

        assertThat(response.body().extra()).containsEntry("details", details);
        assertThat(response.body().extra()).containsEntry("summary", "回溯简报");
    }
}
