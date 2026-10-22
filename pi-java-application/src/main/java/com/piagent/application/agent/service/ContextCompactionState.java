package com.piagent.application.agent.service;

import com.piagent.domain.agent.AgentRun;
import com.piagent.domain.session.model.TokenUsage;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每个 run 的上下文压缩状态。
 *
 * <p>压缩决策需要跨 turn 记住两类信息，它们都<b>不是</b>领域事实，因此不落库、也不放进
 * {@link AgentRun}：</p>
 * <ul>
 *   <li>{@code anchoredInputTokens}：最近一次 provider 上报的输入 token，用于把
 *       字符启发式校准到真实规模（英文/代码场景下启发式常低估）；</li>
 *   <li>{@code lastCompactedTokens}：上一次压缩时的上下文估算，用于触发策略的
 *       “最小增长量”判断；</li>
 *   <li>{@code autoCompactions}：本 run 已执行的自动压缩次数，硬上限为 1，
 *       防止压缩不收敛时每个 turn 都调用一次模型。</li>
 * </ul>
 *
 * <p>run 结束时由 {@code RunRuntime.finish} 调用 {@link #clear} 释放，避免无限增长。</p>
 */
public final class ContextCompactionState {

    /** 单个 run 允许的自动压缩次数上限。 */
    public static final int MAX_AUTO_COMPACTIONS = 1;

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    /**
     * 读取状态的快照。
     *
     * @param runId 运行标识
     * @return 快照；从未记录时返回初始值
     */
    public Snapshot snapshot(String runId) {
        Entry entry = entries.get(runId);
        return entry == null ? Snapshot.INITIAL : entry.snapshot();
    }

    /**
     * 记录本轮的 provider usage。
     *
     * @param runId 运行标识
     * @param usage 本轮用量
     */
    public void recordUsage(String runId, TokenUsage usage) {
        if (usage == null) {
            return;
        }
        entries.computeIfAbsent(runId, key -> new Entry()).recordUsage(usage);
    }

    /**
     * 记录一次成功压缩。
     *
     * @param runId 运行标识
     * @param tokens 压缩时（或压缩后）的上下文估算
     * @param automatic 是否为自动压缩
     */
    public void recordCompaction(String runId, int tokens, boolean automatic) {
        entries.computeIfAbsent(runId, key -> new Entry()).recordCompaction(tokens, automatic);
    }

    /**
     * 释放 run 状态。
     *
     * @param runId 运行标识
     */
    public void clear(String runId) {
        entries.remove(runId);
    }

    /**
     * 状态快照。
     *
     * @param anchoredInputTokens 最近一次 usage 输入 token
     * @param lastCompactedTokens 上次压缩时的估算
     * @param autoCompactions 已执行的自动压缩次数
     */
    public record Snapshot(long anchoredInputTokens, int lastCompactedTokens, int autoCompactions) {

        /** 初始状态。 */
        public static final Snapshot INITIAL = new Snapshot(0, 0, 0);

        /**
         * 是否还允许自动压缩。
         *
         * @return true 表示本 run 尚未用尽自动压缩次数
         */
        public boolean autoCompactionAllowed() {
            return autoCompactions < MAX_AUTO_COMPACTIONS;
        }
    }

    /**
     * 可变状态容器；只在 {@link ConcurrentHashMap#computeIfAbsent} 与同步方法内修改。
     */
    private static final class Entry {

        private long anchoredInputTokens;
        private int lastCompactedTokens;
        private int autoCompactions;

        synchronized void recordUsage(TokenUsage usage) {
            // usage 反映“本次请求实际喂给模型的输入”，会随上下文增长；取较大值可避免
            // 因 provider 省略 usage 或客户端预计算失败导致估算回退。
            anchoredInputTokens = Math.max(anchoredInputTokens, usage.input());
        }

        synchronized void recordCompaction(int tokens, boolean automatic) {
            lastCompactedTokens = Math.max(0, tokens);
            if (automatic) {
                autoCompactions++;
            }
        }

        synchronized Snapshot snapshot() {
            return new Snapshot(anchoredInputTokens, lastCompactedTokens, autoCompactions);
        }
    }
}
