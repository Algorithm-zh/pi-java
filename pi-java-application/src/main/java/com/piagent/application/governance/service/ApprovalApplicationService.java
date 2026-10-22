package com.piagent.application.governance.service;

import com.piagent.application.agent.service.SessionEntryAppender;
import com.piagent.domain.governance.model.ApprovalContext;
import com.piagent.domain.governance.model.ApprovalDecision;
import com.piagent.domain.governance.model.ApprovalId;
import com.piagent.domain.governance.model.ApprovalRequest;
import com.piagent.domain.governance.model.ApprovalStatus;
import com.piagent.domain.governance.port.ApprovalRepository;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.payload.ApprovalPayload;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.shared.IdGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 审批应用服务。
 *
 * <p>负责创建待办、执行单次 CAS 状态迁移、追加 APPROVAL 审计 entry，并在事务
 * 提交后发布 SSE 事件、唤醒等待中的工具线程。审批聚合本身不依赖 Spring，
 * 应用服务也不在等待期间持有数据库事务。</p>
 *
 * <p>决策入口只处理 PENDING：已过期时先落 EXPIRED 再返回；已终态时抛
 * {@code APPROVAL_INVALID_STATE}。HTTP 层根据返回状态把过期请求映射为 409。</p>
 */
@Service
public class ApprovalApplicationService {

    private final ApprovalRepository repository;
    private final SessionEntryAppender appender;
    private final ApprovalWaitRegistry waits;
    private final ApprovalEventPublisher events;
    private final IdGenerator idGenerator;
    private final Clock clock;

    /**
     * 创建审批应用服务。
     *
     * @param repository 审批仓储
     * @param appender 会话 entry 追加器
     * @param waits 等待注册表
     * @param events SSE 事件发布器
     * @param idGenerator 标识生成器
     * @param clock 时钟
     */
    public ApprovalApplicationService(ApprovalRepository repository,
            SessionEntryAppender appender, ApprovalWaitRegistry waits,
            ApprovalEventPublisher events, IdGenerator idGenerator, Clock clock) {
        this.repository = repository;
        this.appender = appender;
        this.waits = waits;
        this.events = events;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    /**
     * 创建待审批请求并返回等待句柄。
     *
     * @param context 被审批的工具调用
     * @param ttl 有效期
     * @return 新建待办与等待句柄
     */
    @Transactional
    public OpenedApproval open(ApprovalContext context, Duration ttl) {
        Objects.requireNonNull(context, "context 不能为空");
        ApprovalRequest request = ApprovalRequest.pending(ApprovalId.newId(idGenerator),
                context, clock.instant(), ttl);
        repository.insert(request);
        appender.markWaitingApproval(request.sessionId());
        CompletableFuture<ApprovalRequest> resolution = waits.register(request.id());
        afterCommit(() -> events.requested(request), () -> waits.remove(request.id()));
        return new OpenedApproval(request, resolution);
    }

    /**
     * 处理用户决策。
     *
     * <p>若待办已过期，会先迁移为 EXPIRED 并返回该终态；调用方应把
     * 非 APPROVED/DENIED 的结果视为冲突。终态重复决策抛
     * {@code APPROVAL_INVALID_STATE}。</p>
     *
     * @param id 审批标识
     * @param decision 决策
     * @param decidedBy 决策者
     * @param reason 意见，可为 null
     * @return 终态审批
     */
    @Transactional
    public ApprovalRequest decide(ApprovalId id, ApprovalDecision decision, String decidedBy,
            String reason) {
        Objects.requireNonNull(decision, "decision 不能为空");
        ApprovalRequest current = require(id);
        if (!current.isPending()) {
            throw invalidState(current);
        }
        Instant now = clock.instant();
        if (current.isExpiredAt(now)) {
            return expireCurrent(current, now);
        }
        ApprovalRequest terminal = current.apply(decision, decidedBy, reason, now);
        if (!repository.updateIfPending(terminal)) {
            throw invalidState(require(id));
        }
        onResolved(terminal);
        return terminal;
    }

    /**
     * 把待办标记为超时。
     *
     * @param id 审批标识
     * @return 终态审批；已终态时返回当前状态
     */
    @Transactional
    public ApprovalRequest expire(ApprovalId id) {
        ApprovalRequest current = require(id);
        if (!current.isPending()) {
            return current;
        }
        return expireCurrent(current, clock.instant());
    }

    /**
     * 把待办标记为运行取消。
     *
     * @param id 审批标识
     * @param reason 取消原因
     * @return 终态审批；已终态时返回当前状态
     */
    @Transactional
    public ApprovalRequest cancel(ApprovalId id, String reason) {
        ApprovalRequest current = require(id);
        if (!current.isPending()) {
            return current;
        }
        ApprovalRequest terminal = current.cancel(reason, clock.instant());
        if (repository.updateIfPending(terminal)) {
            onResolved(terminal);
            return terminal;
        }
        return require(id);
    }

    /**
     * 查询单个审批。
     *
     * @param id 审批标识
     * @return 审批
     */
    public ApprovalRequest find(ApprovalId id) {
        return require(id);
    }

    /**
     * 查询会话下的审批。
     *
     * @param sessionId 会话标识
     * @param status 状态过滤；null 表示全部
     * @return 审批列表
     */
    public List<ApprovalRequest> findBySession(SessionId sessionId, ApprovalStatus status) {
        Objects.requireNonNull(sessionId, "sessionId 不能为空");
        return repository.findBySession(sessionId, status);
    }

    private ApprovalRequest expireCurrent(ApprovalRequest current, Instant now) {
        ApprovalRequest terminal = current.expire(now);
        if (repository.updateIfPending(terminal)) {
            onResolved(terminal);
            return terminal;
        }
        return require(current.id());
    }

    private void onResolved(ApprovalRequest terminal) {
        ApprovalPayload payload = new ApprovalPayload(terminal.id().toString(),
                terminal.toolCallId().toString(), terminal.toolName().toString(),
                terminal.status().name(), terminal.reason(), terminal.decidedBy());
        appender.appendApproval(terminal.sessionId(), payload);
        afterCommit(() -> {
            events.resolved(terminal);
            waits.complete(terminal);
        }, () -> {
            // 提交失败时等待者继续阻塞；gate 会在 TTL 到期后重新读取状态。
        });
    }

    private ApprovalRequest require(ApprovalId id) {
        return repository.findById(id).orElseThrow(() -> new DomainException(
                DomainErrorCode.APPROVAL_NOT_FOUND, "审批不存在: " + id));
    }

    private DomainException invalidState(ApprovalRequest request) {
        return new DomainException(DomainErrorCode.APPROVAL_INVALID_STATE,
                "审批已是终态，不能重复决策: " + request.status());
    }

    private void afterCommit(Runnable committed, Runnable rolledBack) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            committed.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                committed.run();
            }

            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    rolledBack.run();
                }
            }
        });
    }
}
