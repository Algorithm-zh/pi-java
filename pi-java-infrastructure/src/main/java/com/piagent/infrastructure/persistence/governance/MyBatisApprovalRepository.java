package com.piagent.infrastructure.persistence.governance;

import com.piagent.domain.agent.RunId;
import com.piagent.domain.governance.model.ApprovalId;
import com.piagent.domain.governance.model.ApprovalRequest;
import com.piagent.domain.governance.model.ApprovalStatus;
import com.piagent.domain.governance.port.ApprovalRepository;
import com.piagent.domain.session.model.SessionId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 基于 MyBatis 的审批仓储。
 *
 * <p>写入终态时以 {@code status = PENDING} 做 CAS；0 行表示决策、超时或取消
 * 已经有一方先完成迁移，由应用层重新加载后决定下一步。</p>
 */
@Repository
public class MyBatisApprovalRepository implements ApprovalRepository {

    private static final String RESTART_REASON = "服务重启，待办已失效";

    private final ApprovalMapper mapper;
    private final ApprovalPoConverter converter;

    /**
     * 创建仓储。
     *
     * @param mapper MyBatis 映射器
     * @param converter 行转换器
     */
    public MyBatisApprovalRepository(ApprovalMapper mapper, ApprovalPoConverter converter) {
        this.mapper = mapper;
        this.converter = converter;
    }

    @Override
    public void insert(ApprovalRequest request) {
        mapper.insert(converter.toPo(request));
    }

    @Override
    public Optional<ApprovalRequest> findById(ApprovalId id) {
        return Optional.ofNullable(mapper.findById(id.toString())).map(converter::toDomain);
    }

    @Override
    public List<ApprovalRequest> findBySession(SessionId sessionId, ApprovalStatus status) {
        List<ApprovalPO> rows = status == null
                ? mapper.findBySession(sessionId.toString())
                : mapper.findBySessionAndStatus(sessionId.toString(), status.name());
        return rows.stream().map(converter::toDomain).toList();
    }

    @Override
    public List<ApprovalRequest> findByRun(RunId runId) {
        return mapper.findByRun(runId.toString()).stream().map(converter::toDomain).toList();
    }

    @Override
    public boolean updateIfPending(ApprovalRequest request) {
        return mapper.updateIfPending(converter.toPo(request)) == 1;
    }

    @Override
    public int expireAllPending(Instant now) {
        return mapper.expireAllPending(now, RESTART_REASON, ApprovalRequest.SYSTEM_DECIDER);
    }
}
