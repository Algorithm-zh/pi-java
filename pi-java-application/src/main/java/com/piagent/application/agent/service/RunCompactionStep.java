package com.piagent.application.agent.service;

import com.piagent.application.context.CompactionMode;
import com.piagent.application.context.CompactionRequest;
import com.piagent.application.context.CompactionResult;
import com.piagent.application.context.ContextCompactionService;
import com.piagent.application.context.ContextMeasurement;
import com.piagent.application.context.ContextMeasurementFactory;
import com.piagent.domain.agent.RunId;
import com.piagent.domain.context.CompactionDecision;
import com.piagent.domain.context.ContextBudget;
import com.piagent.domain.model.ModelProfile;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.port.SessionEntryStore;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * run 内的上下文压缩步骤。
 *
 * <p>在每次模型请求之前调用：测量当前上下文 → 判断是否触发 → 执行压缩 → 发布 SSE 事件。
 * 它只处理 {@link CompactionMode#AUTO}；{@link CompactionMode#OVERFLOW} 由 Agent Loop
 * 在模型返回溢出错误时主动触发。</p>
 *
 * <p>失败处理：压缩过程中的任何异常（摘要调用失败、摘要为空、落库失败）都被吞掉并
 * 记录日志，返回“未压缩”。理由：压缩是<b>优化</b>而不是任务本身；如果摘要模型暂时不可用，
 * 让对话继续进行（可能随后由 provider 返回溢出错误）比直接让 run 失败更符合用户预期。</p>
 */
@Component
public class RunCompactionStep {

    private static final System.Logger LOG = System.getLogger(RunCompactionStep.class.getName());

    private final SessionEntryStore entryStore;
    private final ContextCompactionService compaction;
    private final ContextCompactionState state;
    private final ContextMeasurementFactory measurements;

    /**
     * 创建压缩步骤。
     *
     * @param entryStore entry 存储
     * @param compaction 压缩编排服务
     * @param state run 级压缩状态
     * @param measurements 测量工厂
     */
    public RunCompactionStep(SessionEntryStore entryStore, ContextCompactionService compaction,
            ContextCompactionState state, ContextMeasurementFactory measurements) {
        this.entryStore = entryStore;
        this.compaction = compaction;
        this.state = state;
        this.measurements = measurements;
    }

    /**
     * 尝试自动压缩。
     *
     * @param session 会话快照
     * @param runId 运行标识
     * @param profile 模型档案
     * @param budget 上下文预算
     * @param events 事件发布器
     * @return 压缩结果；未触发或失败时为空
     */
    public Optional<CompactionResult> tryAutoCompact(ChatSession session, RunId runId,
            ModelProfile profile, ContextBudget budget, RunEventPublisher events) {
        ContextCompactionState.Snapshot snapshot = state.snapshot(runId.toString());
        if (!snapshot.autoCompactionAllowed()) {
            return Optional.empty();
        }
        List<SessionEntry> branch = loadBranch(session);
        ContextMeasurement measurement = measure(session, branch, snapshot, budget);
        CompactionDecision decision = compaction.decide(measurement, snapshot.lastCompactedTokens());
        if (!decision.trigger()) {
            LOG.log(System.Logger.Level.DEBUG, "跳过压缩: {0}", decision.reason());
            return Optional.empty();
        }
        return compress(new CompactionInvocation(session, runId, profile, budget, branch,
                CompactionMode.AUTO, decision.reason(), events), snapshot, measurement);
    }

    /**
     * 在 provider 返回上下文溢出后强制压缩一次。
     *
     * @param session 会话快照
     * @param runId 运行标识
     * @param profile 模型档案
     * @param budget 上下文预算
     * @param events 事件发布器
     * @return 压缩结果；无法压缩时为空
     */
    public Optional<CompactionResult> compactAfterOverflow(ChatSession session, RunId runId,
            ModelProfile profile, ContextBudget budget, RunEventPublisher events) {
        ContextCompactionState.Snapshot snapshot = state.snapshot(runId.toString());
        List<SessionEntry> branch = loadBranch(session);
        ContextMeasurement measurement = measure(session, branch, snapshot, budget);
        return compress(new CompactionInvocation(session, runId, profile, budget, branch,
                CompactionMode.OVERFLOW, "provider 返回上下文溢出", events), snapshot, measurement);
    }

    /**
     * 由模型档案推导上下文预算。
     *
     * <p>统一入口，避免调用方各自计算预算导致触发结论不一致。</p>
     *
     * @param profile 模型档案
     * @return 合法预算
     */
    public ContextBudget budgetOf(ModelProfile profile) {
        return measurements.budgetOf(profile);
    }

    /**
     * 测量上下文（含 usage 锚定）。
     *
     * @param session 会话
     * @param branch 分支
     * @param snapshot 压缩状态
     * @param budget 预算
     * @return 测量结果
     */
    public ContextMeasurement measure(ChatSession session, List<SessionEntry> branch,
            ContextCompactionState.Snapshot snapshot, ContextBudget budget) {
        ContextMeasurement base = measurements.measure(branch, session, budget);
        return compaction.measure(branch, base.branchTokens(), snapshot.anchoredInputTokens(),
                budget);
    }

    private Optional<CompactionResult> compress(CompactionInvocation invocation,
            ContextCompactionState.Snapshot snapshot, ContextMeasurement measurement) {
        RunId runId = invocation.runId();
        CompactionMode mode = invocation.mode();
        RunEventPublisher events = invocation.events();
        int before = measurement.estimate().tokens();
        events.compactionStarted(mode, before, measurement.budget().availableTokens(),
                invocation.reason());
        CompactionRequest request = new CompactionRequest(invocation.session().identity().id(), mode,
                invocation.reason(), invocation.budget(), before, invocation.branch(),
                snapshot.lastCompactedTokens());
        try {
            CompactionResult result = compaction.compact(request, invocation.profile());
            if (!result.applied()) {
                events.compactionCompleted(mode, before, before, false, result.skippedReason());
                return Optional.of(result);
            }
            int after = result.outcome() == null ? before : result.outcome().tokensAfter();
            state.recordCompaction(runId.toString(), after, mode == CompactionMode.AUTO);
            events.compactionCompleted(mode, before, after, result.prunedOnly(), null);
            return Optional.of(result);
        } catch (RuntimeException error) {
            // 压缩是优化步骤：失败时继续对话，由下一层（provider 溢出错误或保守估算）兜底。
            LOG.log(System.Logger.Level.WARNING, "上下文压缩失败，继续本次对话", error);
            events.compactionCompleted(mode, before, before, true, message(error));
            return Optional.empty();
        }
    }

    private List<SessionEntry> loadBranch(ChatSession session) {
        SessionId sessionId = session.identity().id();
        EntryId leaf = session.leafEntryId();
        return leaf == null ? List.of() : entryStore.loadBranch(sessionId, leaf);
    }

    private String message(Throwable error) {
        String text = error.getMessage();
        return text == null || text.isBlank() ? error.getClass().getSimpleName() : text;
    }
}
