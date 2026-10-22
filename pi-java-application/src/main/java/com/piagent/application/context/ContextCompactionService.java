package com.piagent.application.context;

import com.piagent.domain.context.CompactionDecision;
import com.piagent.domain.context.CompactionOutcome;
import com.piagent.domain.context.CompactionPlan;
import com.piagent.domain.context.CompactionPlanner;
import com.piagent.domain.context.CompactionSummaryFormatter;
import com.piagent.domain.context.CompactionTriggerPolicy;
import com.piagent.domain.context.ContextBudget;
import com.piagent.domain.context.EntryTokenCounter;
import com.piagent.domain.context.SummaryDraft;
import com.piagent.domain.context.TokenEstimate;
import com.piagent.domain.context.TokenEstimator;
import com.piagent.domain.model.ModelProfile;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.payload.CompactionPayload;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 压缩编排服务。
 *
 * <p>把领域里各自独立的纯函数串成一次完整压缩：</p>
 * <pre>
 * 测量(measure) → 触发判定(decide) → 规划(plan) → 摘要(summarize)
 *   → 格式化 + 收敛判定 → 追加 COMPACTION entry（由调用方提供的回调落库）
 * </pre>
 *
 * <p>职责边界：本类<b>不</b>直接写数据库，落库通过 {@link CompactionWriter} 回调完成。
 * 原因有两个：一是 Agent Loop 内（会话 RUNNING，叶子用 advanceLeaf）与手动 {@code /compact}
 * （会话 IDLE，叶子用 moveLeaf）的落库语义不同；二是这样可以脱离 Spring 上下文做纯单元测试。</p>
 *
 * <p>失败与降级策略：</p>
 * <ul>
 *   <li>模型摘要调用失败、返回空内容 → 抛异常，由调用方决定是否继续对话；</li>
 *   <li>摘要成功但<b>不收敛</b> → 仍然写入 entry，在 details 里标记 {@code prunedOnly=true}
 *       与 {@code converged=false}，绝不进入“压缩 → 再压缩”的死循环；</li>
 *   <li>可摘要内容太少 → 返回 skipped，不写 entry。</li>
 * </ul>
 */
public final class ContextCompactionService {

    private final EntryTokenCounter tokenCounter;
    private final TokenEstimator estimator;
    private final CompactionTriggerPolicy triggerPolicy;
    private final CompactionPlanner planner;
    private final CompactionSummarizer summarizer;
    private final CompactionSummaryFormatter formatter;
    private final CompactionWriter writer;

    /**
     * 创建压缩服务。
     *
     * @param components 领域组件集合
     * @param writer 落库回调
     */
    public ContextCompactionService(CompactionComponents components, CompactionWriter writer) {
        Objects.requireNonNull(components, "components 不能为空");
        this.tokenCounter = components.tokenCounter();
        this.estimator = components.estimator();
        this.triggerPolicy = components.triggerPolicy();
        this.planner = components.planner();
        this.summarizer = components.summarizer();
        this.formatter = components.formatter();
        this.writer = Objects.requireNonNull(writer, "writer 不能为空");
    }

    /**
     * 测量当前上下文。
     *
     * @param branch 当前分支
     * @param projectedTokens 投影器汇总的上下文 token
     * @param anchoredInputTokens 最近一次 provider 上报的输入 token；未知为 0
     * @param budget 上下文预算
     * @return 测量结果
     */
    public ContextMeasurement measure(List<SessionEntry> branch, int projectedTokens,
            long anchoredInputTokens, ContextBudget budget) {
        Objects.requireNonNull(branch, "branch 不能为空");
        Objects.requireNonNull(budget, "budget 不能为空");
        int branchTokens = Math.max(projectedTokens, tokenCounter.sum(branch));
        TokenEstimate anchored = estimator.anchor(TokenEstimate.heuristic(branchTokens),
                anchoredInputTokens);
        return new ContextMeasurement(anchored, budget, branchTokens);
    }

    /**
     * 判断是否需要自动压缩。
     *
     * @param measurement 测量结果
     * @param lastCompactedTokens 上次压缩时的估算
     * @return 判定结果
     */
    public CompactionDecision decide(ContextMeasurement measurement,
            int lastCompactedTokens) {
        Objects.requireNonNull(measurement, "measurement 不能为空");
        return triggerPolicy.decide(measurement.estimate(), measurement.budget(),
                lastCompactedTokens);
    }

    /**
     * 执行一次压缩。
     *
     * @param request 压缩请求
     * @param profile 用于生成摘要的模型档案
     * @return 结果
     */
    public CompactionResult compact(CompactionRequest request, ModelProfile profile) {
        Objects.requireNonNull(request, "request 不能为空");
        Objects.requireNonNull(profile, "profile 不能为空");
        Optional<CompactionPlan> planned = planner.plan(request.branch(),
                request.currentTokens(), request.budget());
        if (planned.isEmpty()) {
            return CompactionResult.skipped(skipReason(request));
        }
        CompactionPlan plan = planned.get();
        SummaryDraft draft = summarizer.summarize(profile, plan.entriesToSummarize(),
                plan.splitTurnPrefix(), plan.previousSummary());
        String summary = formatter.format(draft);
        int summaryTokens = summarizer.estimateTokens(summary);
        int keptTokens = plan.tokensToKeep();
        int after = summaryTokens + keptTokens;
        CompactionOutcome outcome = new CompactionOutcome(after, plan.tokensBefore(),
                plan.summarizedTokens(), summaryTokens);
        boolean converged = outcome.converged(request.budget());
        Map<String, Object> details = details(request, plan, draft, outcome, converged);
        CompactionPayload payload = new CompactionPayload(summary, plan.firstKeptEntryId(),
                plan.tokensBefore(), details);
        SessionEntry entry = writer.append(request.sessionId(), payload);
        return CompactionResult.applied(entry.id(), summary, outcome, !converged);
    }

    /**
     * 解释规划器为什么没有给出计划。
     *
     * <p>两种情况对用户的意义完全不同：内容太少无从摘要，还是内容仍在保留窗口内
     * 无需摘要。直接笼统地说“没有足够内容”会让人误以为功能没生效。</p>
     */
    private String skipReason(CompactionRequest request) {
        int contextEntries = 0;
        for (SessionEntry entry : request.branch()) {
            if (entry.tokenEstimate() > 0) {
                contextEntries++;
            }
        }
        if (contextEntries < 2) {
            return "没有足够的可摘要内容（至少需要两条参与上下文的消息）";
        }
        return "上下文仍可完整放入保留窗口（保留 " + request.budget().keepRecentTokens()
                + " token），压缩不会带来收益";
    }

    private Map<String, Object> details(CompactionRequest request, CompactionPlan plan,
            SummaryDraft draft, CompactionOutcome outcome, boolean converged) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("mode", request.mode().name());
        details.put("reason", request.reason());
        details.put("splitTurn", plan.splitTurn());
        details.put("tokensAfter", outcome.tokensAfter());
        details.put("summaryTokens", outcome.summaryTokens());
        details.put("converged", converged);
        details.put("prunedOnly", !converged);
        if (!draft.readFiles().isEmpty()) {
            details.put("readFiles", draft.readFiles());
        }
        if (!draft.modifiedFiles().isEmpty()) {
            details.put("modifiedFiles", draft.modifiedFiles());
        }
        return details;
    }
}
