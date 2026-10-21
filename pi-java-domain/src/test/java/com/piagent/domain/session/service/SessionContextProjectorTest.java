package com.piagent.domain.session.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.domain.session.SessionFixtures;
import com.piagent.domain.session.model.ProjectedContext;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.message.BranchSummaryMessage;
import com.piagent.domain.session.model.message.CompactionSummaryMessage;
import com.piagent.domain.session.model.message.TextBlock;
import com.piagent.domain.session.model.message.UserMessage;
import com.piagent.domain.session.model.payload.CustomMessagePayload;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SessionContextProjectorTest {

    private final SessionContextProjector projector = new SessionContextProjector();
    private final SessionId sessionId = SessionFixtures.sessionId(1);

    @Test
    void projectsBranchInOrder() {
        SessionEntry first = SessionFixtures.message(sessionId, 1, null, SessionFixtures.user("hi"));
        SessionEntry second = SessionFixtures.message(sessionId, 2, first.id(), SessionFixtures.assistant("hello"));
        SessionEntry third = SessionFixtures.message(sessionId, 3, second.id(), SessionFixtures.user("again"));

        ProjectedContext context = projector.project(List.of(third, first, second), third.id());

        assertThat(context.messages()).hasSize(3);
        assertThat(context.trace()).containsExactly(first.id(), second.id(), third.id());
        assertThat(context.estimatedTokens()).isEqualTo(30);
    }

    @Test
    void projectsOnlySelectedBranch() {
        SessionEntry root = SessionFixtures.message(sessionId, 1, null, SessionFixtures.user("root"));
        SessionEntry reply = SessionFixtures.message(sessionId, 2, root.id(), SessionFixtures.assistant("reply"));
        SessionEntry branchA = SessionFixtures.message(sessionId, 3, reply.id(), SessionFixtures.user("A"));
        SessionEntry branchB = SessionFixtures.message(sessionId, 4, reply.id(), SessionFixtures.user("B"));

        ProjectedContext context = projector.project(List.of(root, reply, branchA, branchB), branchB.id());

        assertThat(context.messages()).hasSize(3);
        assertThat(((UserMessage) context.messages().get(2)).content().get(0)).isEqualTo(new TextBlock("B"));
        assertThat(context.trace()).containsExactly(root.id(), reply.id(), branchB.id());
    }

    @Test
    void appliesLatestCompaction() {
        SessionEntry first = SessionFixtures.message(sessionId, 1, null, SessionFixtures.user("one"));
        SessionEntry second = SessionFixtures.message(sessionId, 2, first.id(), SessionFixtures.assistant("two"));
        SessionEntry kept = SessionFixtures.message(sessionId, 3, second.id(), SessionFixtures.user("kept"));
        SessionEntry last = SessionFixtures.message(sessionId, 4, kept.id(), SessionFixtures.assistant("last"));
        SessionEntry compaction = SessionFixtures.compaction(sessionId, 5, last.id(), kept.id());
        SessionEntry label = SessionFixtures.label(sessionId, 6, compaction.id(), kept.id());

        ProjectedContext context = projector.project(
                List.of(first, second, kept, last, compaction, label), label.id());

        assertThat(context.messages()).hasSize(3);
        assertThat(context.messages().get(0)).isInstanceOf(CompactionSummaryMessage.class);
        assertThat(context.trace()).containsExactly(compaction.id(), kept.id(), last.id());
        assertThat(context.estimatedTokens()).isEqualTo(50);
    }

    @Test
    void mapsBranchSummaryAndCustomMessage() {
        SessionEntry first = SessionFixtures.message(sessionId, 1, null, SessionFixtures.user("start"));
        SessionEntry summary = SessionFixtures.branchSummary(sessionId, 2, first.id(), first.id());
        SessionEntry custom = SessionFixtures.entry(sessionId, 3, summary.id(),
                new CustomMessagePayload("note", List.of(new TextBlock("note")), true, Map.of()), 5);

        ProjectedContext context = projector.project(List.of(first, summary, custom), custom.id());

        assertThat(context.messages()).hasSize(3);
        assertThat(context.messages().get(1)).isInstanceOf(BranchSummaryMessage.class);
        assertThat(context.messages().get(2)).isInstanceOf(UserMessage.class);
        assertThat(context.estimatedTokens()).isEqualTo(27);
    }

    @Test
    void pairsToolCallsWithResults() {
        SessionEntry first = SessionFixtures.message(sessionId, 1, null, SessionFixtures.user("read"));
        SessionEntry call = SessionFixtures.message(sessionId, 2, first.id(),
                SessionFixtures.assistantWithToolCall("call-1", "read"));
        SessionEntry result = SessionFixtures.message(sessionId, 3, call.id(),
                SessionFixtures.toolResult("call-1", "read"));
        SessionEntry done = SessionFixtures.message(sessionId, 4, result.id(), SessionFixtures.assistant("done"));

        ProjectedContext context = projector.project(List.of(first, call, result, done), done.id());

        assertThat(context.messages()).hasSize(4);
    }

    @Test
    void allowsDanglingToolCallAtTail() {
        SessionEntry first = SessionFixtures.message(sessionId, 1, null, SessionFixtures.user("read"));
        SessionEntry call = SessionFixtures.message(sessionId, 2, first.id(),
                SessionFixtures.assistantWithToolCall("call-1", "read"));

        ProjectedContext context = projector.project(List.of(first, call), call.id());

        assertThat(context.messages()).hasSize(2);
    }

    @Test
    void rejectsUnpairedToolMessages() {
        SessionEntry first = SessionFixtures.message(sessionId, 1, null, SessionFixtures.user("read"));
        SessionEntry call = SessionFixtures.message(sessionId, 2, first.id(),
                SessionFixtures.assistantWithToolCall("call-1", "read"));
        SessionEntry userAfterCall = SessionFixtures.message(sessionId, 3, call.id(), SessionFixtures.user("next"));

        assertThatThrownBy(() -> projector.project(List.of(first, call, userAfterCall), userAfterCall.id()))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.INVALID_ENTRY));

        SessionEntry orphan = SessionFixtures.message(sessionId, 4, first.id(),
                SessionFixtures.toolResult("missing", "read"));
        assertThatThrownBy(() -> projector.project(List.of(first, orphan), orphan.id()))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.TOOL_CALL_MISMATCH));
    }

    @Test
    void rejectsNameMismatchAndCompactionCutOnToolResult() {
        SessionEntry first = SessionFixtures.message(sessionId, 1, null, SessionFixtures.user("read"));
        SessionEntry call = SessionFixtures.message(sessionId, 2, first.id(),
                SessionFixtures.assistantWithToolCall("call-1", "read"));
        SessionEntry wrongName = SessionFixtures.message(sessionId, 3, call.id(),
                SessionFixtures.toolResult("call-1", "write"));
        assertThatThrownBy(() -> projector.project(List.of(first, call, wrongName), wrongName.id()))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.TOOL_CALL_MISMATCH));

        SessionEntry result = SessionFixtures.message(sessionId, 4, call.id(),
                SessionFixtures.toolResult("call-1", "read"));
        SessionEntry compaction = SessionFixtures.compaction(sessionId, 5, result.id(), result.id());
        assertThatThrownBy(() -> projector.project(List.of(first, call, result, compaction), compaction.id()))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.TOOL_CALL_MISMATCH));
    }

    @Test
    void rejectsInvalidBranchInputs() {
        SessionEntry entry = SessionFixtures.message(sessionId, 1, null, SessionFixtures.user("hi"));

        assertThat(projector.project(List.of(entry), null).isEmpty()).isTrue();
        assertThatThrownBy(() -> projector.project(List.of(entry), SessionFixtures.entryId(999)))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.ENTRY_NOT_FOUND));

        SessionEntry foreign = SessionFixtures.message(SessionFixtures.sessionId(2), 2, entry.id(),
                SessionFixtures.user("foreign"));
        assertThatThrownBy(() -> projector.project(List.of(entry, foreign), foreign.id()))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.INVALID_BRANCH));
    }
}
