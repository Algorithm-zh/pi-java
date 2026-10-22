package com.piagent.application.governance.service;

import com.piagent.domain.governance.model.ApprovalRequest;
import java.util.concurrent.CompletableFuture;

/**
 * 审批创建结果。
 *
 * <p>请求与等待句柄在同一事务内建立，调用方拿到 future 后立即开始等待，
 * 不会错过创建提交后立刻发生的决策。</p>
 *
 * @param request 新建的 PENDING 审批
 * @param resolution 决策完成时被写入的 future
 */
public record OpenedApproval(ApprovalRequest request,
        CompletableFuture<ApprovalRequest> resolution) {
}
