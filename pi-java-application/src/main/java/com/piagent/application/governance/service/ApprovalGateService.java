package com.piagent.application.governance.service;

import com.piagent.application.agent.service.ApprovalGate;
import com.piagent.domain.governance.model.ApprovalContext;
import com.piagent.domain.governance.model.ApprovalRequest;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 审批门面的标准实现。
 *
 * <p>创建待办后阻塞在 future 上；HTTP 决策、run 取消与 TTL 到期三条路径都会
 * 把待办迁移到终态。超时与取消由本类发起，决策由应用服务发起，所有迁移都
 * 走同一 CAS，谁先成功谁生效，等待线程最终读取权威终态。</p>
 */
@Service
public class ApprovalGateService implements ApprovalGate {

    private static final long POLL_MILLIS = 200;

    private final ApprovalApplicationService approvals;
    private final Duration approvalTtl;
    private final Clock clock;

    /**
     * 创建审批门面。
     *
     * @param approvals 审批应用服务
     * @param approvalTtl 审批有效期
     * @param clock 时钟
     */
    public ApprovalGateService(ApprovalApplicationService approvals,
            @Value("${pi.agent.approval-ttl:30m}") Duration approvalTtl, Clock clock) {
        this.approvals = approvals;
        this.approvalTtl = approvalTtl;
        this.clock = clock;
    }

    @Override
    public ApprovalRequest await(ApprovalContext context, BooleanSupplier cancelled) {
        OpenedApproval opened = approvals.open(context, approvalTtl);
        ApprovalRequest pending = opened.request();
        CompletableFuture<ApprovalRequest> resolution = opened.resolution();
        while (true) {
            if (resolution.isDone()) {
                return resolution.join();
            }
            if (cancelled.getAsBoolean()) {
                return approvals.cancel(pending.id(), "运行已取消，审批待办失效");
            }
            if (pending.isExpiredAt(clock.instant())) {
                return approvals.expire(pending.id());
            }
            try {
                return resolution.get(POLL_MILLIS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException ignored) {
                // 继续检查取消与过期。
            } catch (InterruptedException error) {
                Thread.interrupted();
                ApprovalRequest result = approvals.cancel(pending.id(), "等待审批的线程被中断");
                Thread.currentThread().interrupt();
                return result;
            } catch (ExecutionException error) {
                throw new IllegalStateException("审批等待异常: " + pending.id(), error.getCause());
            }
        }
    }
}
