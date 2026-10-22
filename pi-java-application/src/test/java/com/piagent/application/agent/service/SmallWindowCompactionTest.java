package com.piagent.application.agent.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.piagent.application.context.CompactionComponents;
import com.piagent.application.context.CompactionMode;
import com.piagent.application.context.CompactionRequest;
import com.piagent.application.context.CompactionResult;
import com.piagent.application.context.CompactionSummarizer;
import com.piagent.application.context.ContextCompactionService;
import com.piagent.application.context.ContextMeasurementFactory;
import com.piagent.application.testsupport.ScriptedLlmGateway;
import com.piagent.application.testsupport.SessionEntryFactory;
import com.piagent.domain.context.CompactionPlanner;
import com.piagent.domain.context.CompactionPromptBuilder;
import com.piagent.domain.context.CompactionSummaryFormatter;
import com.piagent.domain.context.CompactionTriggerPolicy;
import com.piagent.domain.context.ContextBudget;
import com.piagent.domain.context.EntryTokenCounter;
import com.piagent.domain.context.TokenEstimator;
import com.piagent.domain.model.ModelEndpoint;
import com.piagent.domain.model.ModelId;
import com.piagent.domain.model.ModelProfile;
import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.service.SessionContextProjector;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 小上下文窗口下的压缩可用性测试。
 *
 * <p>回归背景：测试与本地 demo 常用很小的上下文窗口（例如 1024），此时自动推导的
 * 保留额度接近可用额度；若规划器在这种情况下不产生计划，溢出压缩就会永远失败。
 * 本测试固定这一场景，保证“窗口再小也能切出切点”。</p>
 */
class SmallWindowCompactionTest {

    private static final SessionId SESSION = SessionEntryFactory.sessionId(11);
    private static final ModelProfile PROFILE = ModelProfile.create(new ModelId("fake"), "Fake",
            ModelEndpoint.fake("fake"));
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"),
            ZoneOffset.UTC);

    @Test
    void compactsWithDerivedBudgetForSmallWindow() {
        ScriptedLlmGateway gateway = new ScriptedLlmGateway();
        gateway.enqueue(ScriptedLlmGateway.text("## 目标\n继续"));
        AtomicReference<SessionEntry> written = new AtomicReference<>();
        TokenEstimator estimator = new TokenEstimator();
        CompactionComponents components = new CompactionComponents(
                new EntryTokenCounter(estimator), estimator, new CompactionTriggerPolicy(0),
                new CompactionPlanner(),
                new CompactionSummarizer(gateway, new CompactionPromptBuilder(), estimator, CLOCK),
                new CompactionSummaryFormatter());
        ContextCompactionService service = new ContextCompactionService(components,
                (sessionId, payload) -> {
                    SessionEntry entry = SessionEntryFactory.entry(sessionId, 900,
                            payload.firstKeptEntryId(), payload, 10);
                    written.set(entry);
                    return entry;
                });
        ContextBudget budget = new ContextMeasurementFactory(
                new SessionContextProjector(), service).budgetOf(PROFILE);
        // 每条 5k token、共 12 条：远超默认 20k 保留额度，接近 111k 可用额度。
        List<SessionEntry> branch = branchOf(12, 5_000);

        CompactionResult result = service.compact(new CompactionRequest(SESSION,
                CompactionMode.OVERFLOW, "测试", budget, 60_000, branch, 0), PROFILE);

        assertThat(result.applied()).isTrue();
        assertThat(written.get()).isNotNull();
        assertThat(gateway.requests()).hasSize(1);
    }

    private List<SessionEntry> branchOf(int count, int tokens) {
        List<SessionEntry> branch = new ArrayList<>();
        EntryId parent = null;
        for (int index = 1; index <= count; index++) {
            boolean user = index % 2 == 1;
            SessionEntry entry = SessionEntryFactory.message(SESSION, index, parent,
                    user ? SessionEntryFactory.user("问题" + index)
                            : SessionEntryFactory.assistant("回答" + index), tokens);
            branch.add(entry);
            parent = entry.id();
        }
        return branch;
    }
}
