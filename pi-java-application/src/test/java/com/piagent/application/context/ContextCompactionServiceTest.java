package com.piagent.application.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.application.testsupport.ScriptedLlmGateway;
import com.piagent.application.testsupport.SessionEntryFactory;
import com.piagent.domain.context.CompactionPlanner;
import com.piagent.domain.context.CompactionPromptBuilder;
import com.piagent.domain.context.CompactionSummaryFormatter;
import com.piagent.domain.context.CompactionTriggerPolicy;
import com.piagent.domain.context.ContextBudget;
import com.piagent.domain.context.EntryTokenCounter;
import com.piagent.domain.context.TokenEstimate;
import com.piagent.domain.context.TokenEstimator;
import com.piagent.domain.model.ModelEndpoint;
import com.piagent.domain.model.ModelId;
import com.piagent.domain.model.ModelProfile;
import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.payload.CompactionPayload;
import com.piagent.domain.shared.DomainException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 压缩编排服务测试。
 *
 * <p>用脚本化 LLM 网关 + 内存回调，验证：测量与 usage 锚定、触发判定、正常压缩、
 * 上一次摘要参与、不收敛降级、摘要为空抛错、可摘要内容不足跳过。</p>
 */
class ContextCompactionServiceTest {

    private static final SessionId SESSION = SessionEntryFactory.sessionId(7);
    private static final ModelProfile PROFILE = ModelProfile.create(new ModelId("fake"),
            "Fake", ModelEndpoint.fake("fake"));
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"),
            ZoneOffset.UTC);

    private final ScriptedLlmGateway gateway = new ScriptedLlmGateway();
    private final AtomicReference<CompactionPayload> written = new AtomicReference<>();
    private final List<SessionEntry> writtenEntries = new ArrayList<>();
    private final ContextCompactionService service = newService();

    @Test
    void measureAnchorsToProviderUsageWhenLarger() {
        List<SessionEntry> branch = branchOf(4, 10);
        ContextBudget budget = new ContextBudget(1_000, 100, 200);

        ContextMeasurement withoutUsage = service.measure(branch, 40, 0, budget);
        ContextMeasurement withUsage = service.measure(branch, 40, 500, budget);

        assertThat(withoutUsage.estimate().method()).isEqualTo(TokenEstimate.Method.HEURISTIC);
        assertThat(withUsage.estimate().method()).isEqualTo(TokenEstimate.Method.USAGE_ANCHOR);
        assertThat(withUsage.estimate().tokens()).isEqualTo(500);
        assertThat(withUsage.exceeds()).isFalse();
    }

    @Test
    void decideSkipsWithinBudgetAndTriggersOnOverflow() {
        ContextBudget budget = new ContextBudget(100, 10, 10);

        assertThat(service.decide(service.measure(branchOf(2, 5), 0, 0, budget), 0).trigger())
                .isFalse();
        assertThat(service.decide(service.measure(branchOf(4, 50), 0, 0, budget), 0).trigger())
                .isTrue();
    }

    @Test
    void compactsAndWritesEntryWithDetails() {
        gateway.enqueue(ScriptedLlmGateway.text("""
                ## 目标
                完成 M7 压缩
                ## 下一步
                跑测试
                ## 关键上下文
                读取文件：README.md
                """));
        List<SessionEntry> branch = branchOf(4, 40);

        CompactionResult result = service.compact(request(branch, 160, 60), PROFILE);

        assertThat(result.applied()).isTrue();
        assertThat(result.prunedOnly()).isFalse();
        assertThat(result.summary()).contains("## 目标", "完成 M7 压缩");
        assertThat(written.get().summary()).isEqualTo(result.summary());
        assertThat(written.get().tokensBefore()).isEqualTo(160);
        assertThat(written.get().details()).containsEntry("mode", "AUTO")
                .containsEntry("converged", true)
                .containsEntry("readFiles", List.of("README.md"))
                .containsKey("tokensAfter");
        assertThat(writtenEntries).hasSize(1);
    }

    @Test
    void passesPreviousSummaryToModel() {
        gateway.enqueue(ScriptedLlmGateway.text("## 目标\n更新后的目标"));
        List<SessionEntry> branch = branchOf(4, 40);
        branch.add(2, SessionEntryFactory.compaction(SESSION, 99, branch.get(0).id(),
                branch.get(1).id()));

        service.compact(request(branch, 160, 20), PROFILE);

        assertThat(lastUserText()).contains("已有摘要").contains("summary");
    }

    @Test
    void marksPrunedOnlyWhenSummaryDoesNotConverge() {
        // 保留窗口设得很小，使规划器一定切出切点；被摘要原文只有 6 token，
        // 而摘要至少几百 token：summaryTokens >= summarizedTokens 必然判为不收敛，
        // 服务仍应写入 entry 并标记 prunedOnly，而不是反复重试。
        gateway.enqueue(ScriptedLlmGateway.text("## 目标\n" + "冗长摘要".repeat(200)));
        List<SessionEntry> branch = branchOf(4, 2);
        ContextBudget tight = new ContextBudget(400, 50, 4);

        CompactionResult result = service.compact(
                new CompactionRequest(SESSION, CompactionMode.AUTO, "测试", tight, 350, branch, 0),
                PROFILE);

        assertThat(result.applied()).isTrue();
        assertThat(result.prunedOnly()).isTrue();
        assertThat(written.get().details()).containsEntry("prunedOnly", true)
                .containsEntry("converged", false);
    }

    @Test
    void failsWhenModelReturnsEmptySummary() {
        gateway.enqueue(ScriptedLlmGateway.text("   "));

        assertThatThrownBy(() -> service.compact(request(branchOf(4, 40), 160, 60), PROFILE))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("没有返回摘要内容");
        assertThat(written.get()).isNull();
    }

    @Test
    void skipsWhenNothingToSummarize() {
        CompactionResult result = service.compact(request(branchOf(1, 40), 40, 60), PROFILE);

        assertThat(result.applied()).isFalse();
        assertThat(result.skippedReason()).contains("没有足够的可摘要内容");
        assertThat(gateway.requests()).isEmpty();
    }

    @Test
    void explainsWhenContextStillFitsKeepWindow() {
        // 4 条各 5 token，全部放得进 60 的保留窗口：压缩没有收益。
        CompactionResult result = service.compact(request(branchOf(4, 5), 20, 60), PROFILE);

        assertThat(result.applied()).isFalse();
        assertThat(result.skippedReason()).contains("仍可完整放入保留窗口");
        assertThat(gateway.requests()).isEmpty();
    }

    private ContextCompactionService newService() {
        TokenEstimator estimator = new TokenEstimator();
        CompactionSummarizer summarizer = new CompactionSummarizer(gateway,
                new CompactionPromptBuilder(), estimator, CLOCK);
        CompactionComponents components = new CompactionComponents(new EntryTokenCounter(estimator),
                estimator, new CompactionTriggerPolicy(0), new CompactionPlanner(), summarizer,
                new CompactionSummaryFormatter());
        return new ContextCompactionService(components, (sessionId, payload) -> {
            written.set(payload);
            SessionEntry entry = SessionEntryFactory.entry(sessionId, 900,
                    payload.firstKeptEntryId(), payload, 30);
            writtenEntries.add(entry);
            return entry;
        });
    }

    private CompactionRequest request(List<SessionEntry> branch, int currentTokens,
            int keepRecent) {
        return new CompactionRequest(SESSION, CompactionMode.AUTO, "上下文超限",
                new ContextBudget(1_000, 100, keepRecent), currentTokens, branch, 0);
    }

    private String lastUserText() {
        List<com.piagent.domain.session.model.message.AgentMessage> messages =
                gateway.requests().get(gateway.requests().size() - 1).messages();
        return ((com.piagent.domain.session.model.message.UserMessage) messages.get(0))
                .content().stream()
                .map(block -> ((com.piagent.domain.session.model.message.TextBlock) block).text())
                .reduce("", String::concat);
    }

    /**
     * 构造 count 条交替出现的用户/助手消息，每条占用 tokens 个估算 token。
     */
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
