package com.piagent.application.agent.service;

import com.piagent.domain.agent.AgentRun;
import com.piagent.domain.agent.RunId;
import com.piagent.domain.model.llm.LlmStream;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Component;

/**
 * 进程内 run 注册表，保证同一会话同时只有一个活跃 run。
 *
 * <p>它不做持久化：run 只是运行态，重启后丢失是预期行为。已结束的 run 会保留
 * 一段时间供 SSE 重连与查询，超过上限时按结束时间淘汰最旧记录。
 */
@Component
public class ActiveRunRegistry {

    private static final int MAX_RETAINED_RUNS = 200;
    private static final int EVICT_DOWN_TO = 150;

    private final ConcurrentMap<RunId, ActiveRun> runs = new ConcurrentHashMap<>();
    private final ConcurrentMap<SessionId, RunId> activeBySession = new ConcurrentHashMap<>();

    /**
     * 注册运行中的 run。
     *
     * @param run 初始快照
     */
    public void register(AgentRun run) {
        RunId existing = activeBySession.putIfAbsent(run.sessionId(), run.id());
        if (existing != null) {
            throw new DomainException(DomainErrorCode.RUN_ALREADY_ACTIVE,
                    "会话已有活跃 run: " + existing);
        }
        runs.put(run.id(), new ActiveRun(run));
        prune();
    }

    /**
     * 放弃一个尚未真正启动的 run，仅用于启动失败清理。
     *
     * @param runId 运行标识
     */
    public void abandon(RunId runId) {
        ActiveRun removed = runs.remove(runId);
        if (removed != null) {
            activeBySession.remove(removed.state().sessionId(), runId);
            removed.countDown();
        }
    }

    /**
     * 按标识查询。
     *
     * @param runId 运行标识
     * @return 快照
     */
    public Optional<AgentRun> find(RunId runId) {
        ActiveRun active = runs.get(runId);
        return active == null ? Optional.empty() : Optional.of(active.state());
    }

    /**
     * 按标识要求 run 存在。
     *
     * @param runId 运行标识
     * @return 快照
     */
    public AgentRun require(RunId runId) {
        return find(runId).orElseThrow(() -> new DomainException(DomainErrorCode.RUN_NOT_FOUND,
                "run 不存在或已被清理: " + runId));
    }

    /**
     * 查询会话当前的活跃 run。
     *
     * @param sessionId 会话标识
     * @return 快照
     */
    public Optional<AgentRun> findActiveBySession(SessionId sessionId) {
        RunId runId = activeBySession.get(sessionId);
        return runId == null ? Optional.empty() : find(runId);
    }

    /**
     * 原子更新运行中的 run。
     *
     * @param runId 运行标识
     * @param change 变更函数
     * @return 更新后的快照
     */
    public AgentRun update(RunId runId, UnaryOperator<AgentRun> change) {
        ActiveRun active = requireActive(runId);
        AgentRun current = active.state();
        if (current.isTerminal()) {
            throw new DomainException(DomainErrorCode.INVALID_STATE_TRANSITION,
                    "run 已结束，不能继续更新: " + runId);
        }
        AgentRun updated = change.apply(current);
        active.state(updated);
        return updated;
    }

    /**
     * 写入终态并释放会话槽位。
     *
     * @param terminal 终态快照
     */
    public void complete(AgentRun terminal) {
        if (!terminal.isTerminal()) {
            throw new DomainException(DomainErrorCode.INVALID_STATE_TRANSITION,
                    "complete 只接受终态 run");
        }
        ActiveRun active = runs.get(terminal.id());
        if (active == null) {
            return;
        }
        active.state(terminal);
        activeBySession.remove(terminal.sessionId(), terminal.id());
        active.countDown();
    }

    /**
     * 请求取消 run。
     *
     * @param runId 运行标识
     * @return true 表示找到并发送了取消信号
     */
    public boolean cancel(RunId runId) {
        ActiveRun active = runs.get(runId);
        if (active == null) {
            return false;
        }
        active.cancel();
        return true;
    }

    /**
     * 请求取消会话当前 run。
     *
     * @param sessionId 会话标识
     * @return 被取消的 run 快照
     */
    public Optional<AgentRun> cancelBySession(SessionId sessionId) {
        RunId runId = activeBySession.get(sessionId);
        if (runId == null) {
            return Optional.empty();
        }
        cancel(runId);
        return find(runId);
    }

    /**
     * 是否已收到取消信号。
     *
     * @param runId 运行标识
     * @return true 表示已取消
     */
    public boolean isCancelled(RunId runId) {
        ActiveRun active = runs.get(runId);
        return active != null && active.isCancelled();
    }

    /**
     * 绑定当前 LLM 流。
     *
     * @param runId 运行标识
     * @param stream 流
     */
    public void attachStream(RunId runId, LlmStream stream) {
        ActiveRun active = runs.get(runId);
        if (active != null) {
            active.attach(stream);
        }
    }

    /**
     * 解除 LLM 流绑定。
     *
     * @param runId 运行标识
     * @param stream 流
     */
    public void clearStream(RunId runId, LlmStream stream) {
        ActiveRun active = runs.get(runId);
        if (active != null) {
            active.clear(stream);
        }
    }

    /**
     * 等待 run 进入终态，供测试与优雅停机使用。
     *
     * @param runId 运行标识
     * @param timeout 最长等待时间
     * @return 终态快照
     */
    public AgentRun awaitTerminal(RunId runId, Duration timeout) {
        ActiveRun active = runs.get(runId);
        if (active == null) {
            throw new DomainException(DomainErrorCode.RUN_NOT_FOUND, "run 不存在: " + runId);
        }
        try {
            if (!active.await(timeout)) {
                throw new DomainException(DomainErrorCode.INVALID_STATE_TRANSITION,
                        "等待 run 结束超时: " + runId);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new DomainException(DomainErrorCode.INVALID_STATE_TRANSITION,
                    "等待 run 结束时被中断: " + runId, error);
        }
        return active.state();
    }

    private ActiveRun requireActive(RunId runId) {
        ActiveRun active = runs.get(runId);
        if (active == null) {
            throw new DomainException(DomainErrorCode.RUN_NOT_FOUND, "run 不存在: " + runId);
        }
        return active;
    }

    private void prune() {
        if (runs.size() <= MAX_RETAINED_RUNS) {
            return;
        }
        List<AgentRun> terminal = runs.values().stream()
                .map(ActiveRun::state)
                .filter(AgentRun::isTerminal)
                .sorted(Comparator.comparing(AgentRun::finishedAt))
                .toList();
        for (AgentRun run : terminal) {
            if (runs.size() <= EVICT_DOWN_TO) {
                break;
            }
            runs.remove(run.id());
        }
    }
}
