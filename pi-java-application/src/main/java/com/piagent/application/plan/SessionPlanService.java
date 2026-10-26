package com.piagent.application.plan;

import com.piagent.application.agent.service.RunAuxEvents;
import com.piagent.application.agent.service.RunRuntime;
import com.piagent.application.agent.service.SessionEntryAppender;
import com.piagent.domain.plan.model.Plan;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.payload.PlanPayload;
import com.piagent.domain.session.model.payload.VerificationPayload;
import com.piagent.domain.session.port.SessionEntryStore;
import com.piagent.domain.session.port.SessionRepository;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 会话计划用例：读取分支上最新的快照、全量替换并广播。
 *
 * <p>计划存放在会话日志中而不是独立投影表：更新频率低、需要随分支切换，
 * 最后一条 PLAN entry 就是当前事实。读取时只扫描当前叶子的祖先链。</p>
 */
@Service
public class SessionPlanService {

    private final SessionRepository sessions;
    private final SessionEntryStore entries;
    private final SessionEntryAppender appender;
    private final RunRuntime runtime;
    private final RunAuxEvents events;

    /**
     * 创建服务。
     *
     * @param sessions 会话仓储
     * @param entries entry 存储
     * @param appender entry 追加器
     * @param runtime 运行运行时
     * @param events 辅助事件发布器
     */
    public SessionPlanService(SessionRepository sessions, SessionEntryStore entries,
            SessionEntryAppender appender, RunRuntime runtime, RunAuxEvents events) {
        this.sessions = sessions;
        this.entries = entries;
        this.appender = appender;
        this.runtime = runtime;
        this.events = events;
    }

    /**
     * 读取当前计划与最近一次验证。
     *
     * @param sessionId 会话标识
     * @return 快照；从未更新过时为空计划与空验证
     */
    public SessionPlanSnapshot snapshot(SessionId sessionId) {
        ChatSession session = require(sessionId);
        if (session.leafEntryId() == null) {
            return new SessionPlanSnapshot(Plan.empty(), null);
        }
        List<SessionEntry> branch = entries.loadBranch(sessionId, session.leafEntryId());
        PlanPayload plan = null;
        VerificationPayload verification = null;
        for (int index = branch.size() - 1; index >= 0; index--) {
            Object payload = branch.get(index).payload();
            if (plan == null && payload instanceof PlanPayload value) {
                plan = value;
            }
            if (verification == null && payload instanceof VerificationPayload value) {
                verification = value;
            }
            if (plan != null && verification != null) {
                break;
            }
        }
        return new SessionPlanSnapshot(plan == null ? Plan.empty() : plan.plan(), verification);
    }

    /**
     * 用一份新计划替换当前计划；模型为空列表时等价于清空。
     *
     * @param sessionId 会话标识
     * @param plan 新计划
     * @return 已持久化的计划
     */
    public Plan replace(SessionId sessionId, Plan plan) {
        Optional<String> runId = runtime.findActiveBySession(sessionId)
                .map(run -> run.id().toString());
        PlanPayload payload = new PlanPayload(runId.orElse(""), plan.items());
        appender.appendMetadata(sessionId, payload);
        events.planUpdated(sessionId, payload);
        return plan;
    }

    private ChatSession require(SessionId sessionId) {
        return sessions.findById(sessionId)
                .orElseThrow(() -> new DomainException(DomainErrorCode.SESSION_NOT_FOUND,
                        "会话不存在: " + sessionId));
    }
}
