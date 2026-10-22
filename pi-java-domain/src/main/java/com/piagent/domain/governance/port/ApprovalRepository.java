package com.piagent.domain.governance.port;

import com.piagent.domain.agent.RunId;
import com.piagent.domain.governance.model.ApprovalId;
import com.piagent.domain.governance.model.ApprovalRequest;
import com.piagent.domain.governance.model.ApprovalStatus;
import com.piagent.domain.session.model.SessionId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 审批待办仓储。
 *
 * <p>该表是待办投影，不是审计事实源：终态必须同时写入会话 {@code APPROVAL}
 * entry。更新使用 {@code status = PENDING} 作为 CAS 条件，保证决策、超时与取消
 * 并发到达时只有一个迁移成功。</p>
 */
public interface ApprovalRepository {

    /**
     * 插入待审批请求。
     *
     * @param request 审批聚合
     */
    void insert(ApprovalRequest request);

    /**
     * 按标识查询。
     *
     * @param id 审批标识
     * @return 聚合
     */
    Optional<ApprovalRequest> findById(ApprovalId id);

    /**
     * 查询会话的审批记录，可按状态过滤。
     *
     * @param sessionId 会话标识
     * @param status 状态过滤；null 表示全部
     * @return 按创建时间倒序的记录
     */
    List<ApprovalRequest> findBySession(SessionId sessionId, ApprovalStatus status);

    /**
     * 查询一次 run 的审批记录。
     *
     * @param runId 运行标识
     * @return 记录
     */
    List<ApprovalRequest> findByRun(RunId runId);

    /**
     * 仅当数据库中的记录仍为 PENDING 时写入终态。
     *
     * @param request 已迁移到终态的聚合
     * @return true 表示本次更新成功；false 表示已被其他决策抢先
     */
    boolean updateIfPending(ApprovalRequest request);

    /**
     * 把遗留的 PENDING 记录批量标记为 EXPIRED，供启动恢复使用。
     *
     * <p>进程重启后内存中的 run 已不存在，待办不可能再被处理；这里不追加
     * 会话 entry，因为对应 run 的审计链已经中断。返回值用于启动日志。</p>
     *
     * @param now 过期处理时间
     * @return 更新的记录数
     */
    int expireAllPending(Instant now);
}
