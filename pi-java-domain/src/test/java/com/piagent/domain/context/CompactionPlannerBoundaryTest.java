package com.piagent.domain.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.piagent.domain.session.SessionFixtures;
import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.payload.MessagePayload;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 压缩规划器边界条件补充测试。
 *
 * <p>核心语义：只有当上下文<b>确实超出保留窗口</b>时才产生计划；否则压缩没有收益，
 * 返回空计划，避免把极短对话摘要成“摘要的摘要”。</p>
 */
class CompactionPlannerBoundaryTest {

    private static final SessionId SESSION = SessionFixtures.sessionId(3);

    private final CompactionPlanner planner = new CompactionPlanner();

    @Test
    void skipsWhenKeepWindowCoversWholeContext() {
        List<SessionEntry> branch = branchOf(4, 2);

        assertThat(planner.plan(branch, 8, new ContextBudget(400, 50, 20))).isEmpty();
    }

    @Test
    void plansWhenContextExceedsKeepWindow() {
        List<SessionEntry> branch = branchOf(4, 40);

        CompactionPlan plan = planner.plan(branch, 160, new ContextBudget(400, 50, 60))
                .orElseThrow();

        assertThat(plan.entriesToSummarize())
                .containsExactly(branch.get(0), branch.get(1), branch.get(2));
        assertThat(plan.firstKeptEntryId()).isEqualTo(branch.get(3).id());
    }

    @Test
    void returnsEmptyForSingleEntry() {
        assertThat(planner.plan(branchOf(1, 50), 50, new ContextBudget(100, 10, 10))).isEmpty();
    }

    private List<SessionEntry> branchOf(int count, int tokens) {
        List<SessionEntry> branch = new ArrayList<>();
        EntryId parent = null;
        for (int index = 1; index <= count; index++) {
            boolean user = index % 2 == 1;
            SessionEntry entry = SessionFixtures.entry(SESSION, index, parent,
                    new MessagePayload(user ? SessionFixtures.user("问题" + index)
                            : SessionFixtures.assistant("回答" + index)), tokens);
            branch.add(entry);
            parent = entry.id();
        }
        return branch;
    }
}
