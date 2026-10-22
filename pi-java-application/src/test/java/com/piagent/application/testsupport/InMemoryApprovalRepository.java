package com.piagent.application.testsupport;

import com.piagent.domain.agent.RunId;
import com.piagent.domain.governance.model.ApprovalId;
import com.piagent.domain.governance.model.ApprovalRequest;
import com.piagent.domain.governance.model.ApprovalStatus;
import com.piagent.domain.governance.port.ApprovalRepository;
import com.piagent.domain.session.model.SessionId;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 应用层单元测试用的内存审批仓储，模拟 PENDING CAS 语义。
 */
public final class InMemoryApprovalRepository implements ApprovalRepository {

    private final Map<ApprovalId, ApprovalRequest> records = new LinkedHashMap<>();

    @Override
    public void insert(ApprovalRequest request) {
        boolean duplicate = records.values().stream()
                .anyMatch(item -> item.runId().equals(request.runId())
                        && item.toolCallId().equals(request.toolCallId()));
        if (duplicate) {
            throw new IllegalStateException("重复的 run/toolCall: " + request.toolCallId());
        }
        records.put(request.id(), request);
    }

    @Override
    public Optional<ApprovalRequest> findById(ApprovalId id) {
        return Optional.ofNullable(records.get(id));
    }

    @Override
    public List<ApprovalRequest> findBySession(SessionId sessionId, ApprovalStatus status) {
        return records.values().stream()
                .filter(item -> item.sessionId().equals(sessionId))
                .filter(item -> status == null || item.status() == status)
                .toList();
    }

    @Override
    public List<ApprovalRequest> findByRun(RunId runId) {
        return records.values().stream()
                .filter(item -> item.runId().equals(runId))
                .toList();
    }

    @Override
    public boolean updateIfPending(ApprovalRequest request) {
        ApprovalRequest current = records.get(request.id());
        if (current == null || !current.isPending()) {
            return false;
        }
        records.put(request.id(), request);
        return true;
    }

    @Override
    public int expireAllPending(Instant now) {
        int expired = 0;
        for (Map.Entry<ApprovalId, ApprovalRequest> entry : records.entrySet()) {
            ApprovalRequest current = entry.getValue();
            if (current.isPending()) {
                entry.setValue(current.expire(now));
                expired++;
            }
        }
        return expired;
    }
}
