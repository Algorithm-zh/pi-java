package com.piagent.application.context;

import com.piagent.domain.context.ContextBudget;
import com.piagent.domain.model.ModelProfile;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.service.SessionContextProjector;
import java.util.List;

/**
 * 上下文测量工厂。
 *
 * <p>把“投影 → 取 token → 推导预算”这三步固定在一处：run 内压缩与手动压缩都用它，
 * 避免两条路径因为参数拼装不同而得出不同的触发结论。</p>
 *
 * <p>无状态、线程安全。</p>
 */
public final class ContextMeasurementFactory {

    private static final double MAX_KEEP_RATIO = 0.8;
    private static final int DEFAULT_RESERVE = 16_384;
    private static final int DEFAULT_KEEP = 20_000;
    private static final int MIN_WINDOW = 1_024;

    private final SessionContextProjector projector;
    private final ContextCompactionService compaction;

    /**
     * 创建工厂。
     *
     * @param projector 投影器
     * @param compaction 压缩编排（复用其测量逻辑）
     */
    public ContextMeasurementFactory(SessionContextProjector projector,
            ContextCompactionService compaction) {
        this.projector = projector;
        this.compaction = compaction;
    }

    /**
     * 由模型端点推导合法预算。
     *
     * <p>窗口极小时（例如测试或小模型），直接使用默认保留额度会让
     * {@code keepRecentTokens >= availableTokens} 校验失败，因此这里收敛保留额度与预留额度。</p>
     *
     * @param profile 模型档案
     * @return 合法预算
     */
    public ContextBudget budgetOf(ModelProfile profile) {
        int window = Math.max(profile.endpoint().contextWindow(), MIN_WINDOW);
        int reserve = Math.max(1, Math.min(DEFAULT_RESERVE, window / 2));
        int available = window - reserve;
        int keep = Math.min(DEFAULT_KEEP, Math.max(1, (int) Math.floor(available * MAX_KEEP_RATIO)));
        if (keep >= available) {
            keep = Math.max(0, available - 1);
        }
        return new ContextBudget(window, reserve, keep);
    }

    /**
     * 测量上下文。
     *
     * @param branch 分支
     * @param session 会话
     * @param budget 预算
     * @return 测量结果
     */
    public ContextMeasurement measure(List<SessionEntry> branch, ChatSession session,
            ContextBudget budget) {
        EntryId leaf = session.leafEntryId();
        int projected = leaf == null ? 0 : projector.project(branch, leaf).estimatedTokens();
        return compaction.measure(branch, projected, 0, budget);
    }
}
