package com.piagent.domain.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.piagent.domain.session.SessionFixtures;
import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.message.AgentMessage;
import com.piagent.domain.session.model.payload.MessagePayload;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 压缩规划器测试。
 *
 * <p>重点验证切点不落在 toolResult、保留窗口、split-turn 与旧摘要携带。</p>
 */
class CompactionPlannerTest {

    private static final SessionId SESSION = SessionFixtures.sessionId(1);

    private final CompactionPlanner planner = new CompactionPlanner();

    @Test
    void skipsCompactionWhenEverythingFits() {
        List<SessionEntry> branch = new ArrayList<>();
        branch.add(message(1, null, SessionFixtures.user("one"), 10));
        branch.add(message(2, branch.get(0).id(), SessionFixtures.assistant("two"), 10));

        ContextBudget budget = new ContextBudget(100, 10, 20);

        assertThat(planner.plan(branch, 20, budget)).isEmpty();
    }

    @Test
    void keepsRecentEntriesAndCutsAtUserBoundary() {
        List<SessionEntry> branch = new ArrayList<>();
        branch.add(message(1, null, SessionFixtures.user("one"), 10));
        branch.add(message(2, branch.get(0).id(), SessionFixtures.assistant("two"), 10));
        branch.add(message(3, branch.get(1).id(), SessionFixtures.user("three"), 10));
        branch.add(message(4, branch.get(2).id(), SessionFixtures.assistant("four"), 10));

        CompactionPlan plan = planner.plan(branch, 40,
                new ContextBudget(100, 10, 20)).orElseThrow();

        assertThat(plan.entriesToSummarize()).containsExactly(branch.get(0), branch.get(1));
        assertThat(plan.firstKeptEntryId()).isEqualTo(branch.get(2).id());
        assertThat(plan.tokensBefore()).isEqualTo(40);
        assertThat(plan.tokensToKeep()).isEqualTo(20);
        assertThat(plan.splitTurn()).isFalse();
        assertThat(plan.previousSummary()).isNull();
    }

    @Test
    void neverStartsWithToolResult() {
        List<SessionEntry> branch = new ArrayList<>();
        branch.add(message(1, null, SessionFixtures.user("read file"), 10));
        branch.add(message(2, branch.get(0).id(),
                SessionFixtures.assistantWithToolCall("call-1", "read"), 10));
        branch.add(message(3, branch.get(1).id(),
                SessionFixtures.toolResult("call-1", "read"), 10));
        branch.add(message(4, branch.get(2).id(), SessionFixtures.assistant("done"), 10));

        CompactionPlan plan = planner.plan(branch, 40,
                new ContextBudget(100, 10, 20)).orElseThrow();

        assertThat(plan.firstKeptEntryId()).isEqualTo(branch.get(3).id());
        assertThat(plan.entriesToSummarize()).containsExactly(
                branch.get(0), branch.get(1), branch.get(2));
    }

    @Test
    void marksSplitTurnWhenSingleTurnExceedsKeepRecent() {
        List<SessionEntry> branch = new ArrayList<>();
        branch.add(message(1, null, SessionFixtures.user("one"), 10));
        branch.add(message(2, branch.get(0).id(), SessionFixtures.assistant("two"), 10));
        branch.add(message(3, branch.get(1).id(), SessionFixtures.user("long task"), 10));
        branch.add(message(4, branch.get(2).id(), SessionFixtures.assistant("huge answer"), 100));

        CompactionPlan plan = planner.plan(branch, 130,
                new ContextBudget(200, 10, 50)).orElseThrow();

        assertThat(plan.splitTurn()).isTrue();
        assertThat(plan.splitTurnPrefix()).containsExactly(branch.get(2));
        assertThat(plan.entriesToSummarize()).hasSize(3);
        assertThat(plan.firstKeptEntryId()).isEqualTo(branch.get(3).id());
    }

    @Test
    void carriesPreviousSummaryFromLatestCompaction() {
        List<SessionEntry> branch = new ArrayList<>();
        branch.add(message(1, null, SessionFixtures.user("one"), 10));
        branch.add(message(2, branch.get(0).id(), SessionFixtures.assistant("two"), 10));
        branch.add(SessionFixtures.compaction(SESSION, 3, branch.get(1).id(), branch.get(0).id()));
        branch.add(message(4, branch.get(2).id(), SessionFixtures.user("three"), 20));
        branch.add(message(5, branch.get(3).id(), SessionFixtures.assistant("four"), 20));
        branch.add(message(6, branch.get(4).id(), SessionFixtures.user("five"), 20));
        branch.add(message(7, branch.get(5).id(), SessionFixtures.assistant("six"), 20));

        CompactionPlan plan = planner.plan(branch, 130,
                new ContextBudget(300, 10, 40)).orElseThrow();

        assertThat(plan.previousSummary()).isEqualTo("summary");
        assertThat(plan.firstKeptEntryId()).isEqualTo(branch.get(5).id());
        assertThat(plan.entriesToSummarize()).containsExactly(
                branch.get(0), branch.get(1), branch.get(3), branch.get(4));
    }

    private SessionEntry message(long seq, EntryId parent, AgentMessage message, int tokens) {
        return SessionFixtures.entry(SESSION, seq, parent, new MessagePayload(message), tokens);
    }
}
