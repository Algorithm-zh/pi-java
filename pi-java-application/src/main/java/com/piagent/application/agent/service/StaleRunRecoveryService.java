package com.piagent.application.agent.service;

import com.piagent.application.governance.service.ApprovalApplicationService;
import com.piagent.domain.governance.model.ApprovalStatus;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.SessionStatus;
import com.piagent.domain.session.port.SessionRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 启动自愈：清理进程重启后遗留的运行态。
 *
 * <p>run 只存在于进程内存，进程退出后数据库里的 RUNNING / WAITING_APPROVAL
 * 不可能再推进。若不复位，会话既不能继续发送消息，前端还会弹出一个永远无法
 * 决策的审批框。这里在启动时统一取消遗留审批、给悬空的工具调用补写错误结果，
 * 再把会话复位为空闲；空闲会话上遗留的悬空调用也会一并修复。多实例部署需要
 * 租约，见文档中的限制说明。</p>
 */
@Service
public class StaleRunRecoveryService {

    private static final int SCAN_LIMIT = 500;

    private final SessionRepository sessions;
    private final RunRuntime runtime;
    private final ApprovalApplicationService approvals;
    private final InterruptedToolCallCloser closer;

    /**
     * 创建恢复服务。
     *
     * @param sessions 会话仓储
     * @param runtime 运行运行时
     * @param approvals 审批用例
     * @param closer 悬空工具调用闭合器
     */
    public StaleRunRecoveryService(SessionRepository sessions, RunRuntime runtime,
            ApprovalApplicationService approvals, InterruptedToolCallCloser closer) {
        this.sessions = sessions;
        this.runtime = runtime;
        this.approvals = approvals;
        this.closer = closer;
    }

    /**
     * 复位所有内存中没有对应 run 的运行态会话，并修复悬空工具调用。
     *
     * @return 处理过的会话数量
     */
    public int recover() {
        int recovered = 0;
        Set<SessionId> handled = new HashSet<>();
        for (ChatSession session : candidates()) {
            SessionId sessionId = session.identity().id();
            if (!handled.add(sessionId) || runtime.findActiveBySession(sessionId).isPresent()) {
                continue;
            }
            try {
                boolean stuck = session.status() == SessionStatus.RUNNING
                        || session.status() == SessionStatus.WAITING_APPROVAL;
                if (stuck) {
                    approvals.findBySession(sessionId, ApprovalStatus.PENDING)
                            .forEach(approval -> approvals.cancel(approval.id(), "server-restart"));
                }
                int closed = closer.closeDangling(sessionId);
                if (stuck) {
                    runtime.forceIdle(sessionId);
                    recovered++;
                } else if (closed > 0) {
                    recovered++;
                }
            } catch (RuntimeException ignored) {
                // 单个会话恢复失败不应阻断其他会话；用户仍可手动停止后继续。
            }
        }
        return recovered;
    }

    private List<ChatSession> candidates() {
        List<ChatSession> candidates = new ArrayList<>(sessions.findStuck(SCAN_LIMIT));
        for (ChatSession session : sessions.findRecent(SCAN_LIMIT)) {
            if (session.status() != SessionStatus.ARCHIVED) {
                candidates.add(session);
            }
        }
        return candidates;
    }
}
