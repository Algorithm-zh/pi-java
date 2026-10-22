package com.piagent.application.governance.service;

import com.piagent.domain.governance.model.ApprovalId;
import com.piagent.domain.governance.model.ApprovalRequest;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/**
 * 进程内审批等待注册表。
 *
 * <p>审批决策由 HTTP 线程写入，工具线程在同一进程内等待。决策提交后通过
 * {@link CompletableFuture} 唤醒等待者，避免以数据库轮询作为主通道。注册表
 * 只保存未完成的等待；进程重启后由启动过期流程清理遗留待办。</p>
 */
@Component
public class ApprovalWaitRegistry {

    private final ConcurrentMap<ApprovalId, CompletableFuture<ApprovalRequest>> waits
            = new ConcurrentHashMap<>();

    /**
     * 注册一个等待。
     *
     * @param approvalId 审批标识
     * @return 等待中的结果 future
     */
    public CompletableFuture<ApprovalRequest> register(ApprovalId approvalId) {
        CompletableFuture<ApprovalRequest> future = new CompletableFuture<>();
        CompletableFuture<ApprovalRequest> existing = waits.putIfAbsent(approvalId, future);
        return existing == null ? future : existing;
    }

    /**
     * 以终态唤醒等待者；没有等待者时静默忽略。
     *
     * @param request 终态审批
     */
    public void complete(ApprovalRequest request) {
        CompletableFuture<ApprovalRequest> future = waits.remove(request.id());
        if (future != null) {
            future.complete(request);
        }
    }

    /**
     * 移除尚未完成的等待，用于创建事务回滚。
     *
     * @param approvalId 审批标识
     */
    public void remove(ApprovalId approvalId) {
        waits.remove(approvalId);
    }
}
