package com.piagent.application.context;

import com.piagent.domain.context.ContextBudget;
import com.piagent.domain.model.ModelProfile;
import com.piagent.domain.model.port.ModelProfileRepository;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.SessionStatus;
import com.piagent.domain.session.port.SessionEntryStore;
import com.piagent.domain.session.port.SessionRepository;
import com.piagent.domain.session.service.SessionContextProjector;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.shared.Guard;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 手动压缩用例（{@code /compact}）。
 *
 * <p>与 run 内自动压缩的区别：</p>
 * <ul>
 *   <li>只允许在会话 IDLE 时执行——运行中压缩会与正在推进的叶子竞争；</li>
 *   <li>不受“每个 run 最多一次”限制，用户可以反复整理上下文；</li>
 *   <li>仍受规划器约束：内容仍在保留窗口内时返回“未执行”，不会把短对话压缩成摘要。</li>
 * </ul>
 *
 * <p>事务边界：摘要模型调用发生在事务之外（可能耗时数秒到数十秒），只有写 entry
 * 的一刻才开启短事务（由 {@code SessionEntryAppender.appendCompaction} 的
 * {@code @Transactional} 保证）。</p>
 */
@Service
public class CompactionApplicationService {

    private final SessionRepository sessions;
    private final SessionEntryStore entryStore;
    private final SessionContextProjector projector;
    private final ContextCompactionService compaction;
    private final ContextMeasurementFactory measurements;
    private final ModelProfileRepository models;

    /**
     * 创建用例服务。
     *
     * @param sessions 会话仓储
     * @param entryStore entry 存储
     * @param projector 上下文投影器
     * @param compaction 压缩编排
     * @param measurements 测量工厂
     * @param models 模型档案仓储
     */
    public CompactionApplicationService(SessionRepository sessions, SessionEntryStore entryStore,
            SessionContextProjector projector, ContextCompactionService compaction,
            ContextMeasurementFactory measurements, ModelProfileRepository models) {
        this.sessions = sessions;
        this.entryStore = entryStore;
        this.projector = projector;
        this.compaction = compaction;
        this.measurements = measurements;
        this.models = models;
    }

    /**
     * 手动压缩空闲会话。
     *
     * @param sessionId 会话标识
     * @return 结果视图
     */
    public ManualCompactionView compact(SessionId sessionId) {
        ChatSession session = sessions.findById(sessionId)
                .orElseThrow(() -> new DomainException(DomainErrorCode.SESSION_NOT_FOUND,
                        "会话不存在: " + sessionId));
        Guard.require(session.status() == SessionStatus.IDLE,
                DomainErrorCode.INVALID_STATE_TRANSITION,
                "只有空闲会话可以手动压缩，当前状态: " + session.status());
        if (session.leafEntryId() == null) {
            return ManualCompactionView.skipped(0, "会话还没有任何消息");
        }
        ModelProfile profile = models.findById(new com.piagent.domain.model.ModelId(
                        session.config().modelProfileId()))
                .orElseThrow(() -> new DomainException(DomainErrorCode.MODEL_NOT_FOUND,
                        "模型档案不存在: " + session.config().modelProfileId()));
        ContextBudget budget = measurements.budgetOf(profile);
        List<SessionEntry> branch = entryStore.loadBranch(sessionId, session.leafEntryId());
        ContextMeasurement measurement = measurements.measure(branch, session, budget);
        CompactionResult result = compaction.compact(new CompactionRequest(sessionId,
                CompactionMode.MANUAL, "用户请求 /compact", budget, measurement.estimate().tokens(),
                branch, 0), profile);
        if (!result.applied()) {
            return ManualCompactionView.skipped(measurement.estimate().tokens(),
                    result.skippedReason());
        }
        int after = result.outcome() == null ? measurement.estimate().tokens()
                : result.outcome().tokensAfter();
        EntryId entryId = result.entryId();
        return ManualCompactionView.applied(entryId, measurement.estimate().tokens(), after,
                result.summary(), result.prunedOnly());
    }

    /**
     * 估算当前上下文 token，供 UI 展示。
     *
     * <p>只做纯投影估算，不做 usage 锚定：UI 只需数量级，且此接口可能在 run 结束后调用，
     * 那时内存中的 run 状态已被释放。</p>
     *
     * @param sessionId 会话标识
     * @return token 估算
     */
    public int estimateContextTokens(SessionId sessionId) {
        ChatSession session = require(sessionId);
        return estimate(session);
    }

    /**
     * 查询上下文用量与模型窗口，供头部仪表显示。
     *
     * @param sessionId 会话标识
     * @return 上下文状态
     */
    public ContextStatusView contextStatus(SessionId sessionId) {
        ChatSession session = require(sessionId);
        ModelProfile profile = models.findById(new com.piagent.domain.model.ModelId(
                        session.config().modelProfileId()))
                .orElseThrow(() -> new DomainException(DomainErrorCode.MODEL_NOT_FOUND,
                        "模型档案不存在: " + session.config().modelProfileId()));
        ContextBudget budget = measurements.budgetOf(profile);
        return new ContextStatusView(estimate(session), profile.endpoint().contextWindow(),
                budget.availableTokens(), profile.endpoint().maxOutputTokens(),
                profile.id().value(), profile.endpoint().modelName(),
                profile.endpoint().provider().name());
    }

    private int estimate(ChatSession session) {
        EntryId leaf = session.leafEntryId();
        if (leaf == null) {
            return 0;
        }
        return projector.project(
                entryStore.loadBranch(session.identity().id(), leaf), leaf).estimatedTokens();
    }

    private ChatSession require(SessionId sessionId) {
        return sessions.findById(sessionId)
                .orElseThrow(() -> new DomainException(DomainErrorCode.SESSION_NOT_FOUND,
                        "会话不存在: " + sessionId));
    }
}
