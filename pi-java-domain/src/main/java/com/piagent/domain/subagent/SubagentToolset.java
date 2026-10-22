package com.piagent.domain.subagent;

import com.piagent.domain.tool.model.ToolDescriptor;
import com.piagent.domain.tool.model.ToolName;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 计算子代理可用的工具子集。
 *
 * <p>规则：<b>子集只能收紧，不能放宽</b>。</p>
 * <ol>
 *   <li>起点是父 run 冻结的工具快照；</li>
 *   <li>若请求带白名单，取交集——父没有的工具不会因为白名单而出现；</li>
 *   <li>若子会话深度已达策略上限，移除 {@code subagent} 工具，让模型从目录上
 *       就知道不能再分派，而不是调用后才发现被拒绝。</li>
 * </ol>
 *
 * <p>纯函数、无状态，便于直接断言边界。</p>
 */
public final class SubagentToolset {

    /** 子代理工具名：既用于注册，也用于深度裁剪。 */
    public static final String SUBAGENT_TOOL = "subagent";

    private SubagentToolset() {
    }

    /**
     * 计算工具子集。
     *
     * <p>深度语义：{@code childDepth} 是<b>即将创建的子会话</b>的深度。子会话里如果
     * 再派生代理，那个孙代理的深度就是 {@code childDepth + 1}；若超过 {@code maxDepth}，
     * 就把 {@code subagent} 从子工具集中移除。</p>
     *
     * @param parentTools 父 run 的工具快照
     * @param request 子代理请求（可能带白名单）
     * @param childDepth 即将创建的子会话深度（用户会话的直接子代理为 1）
     * @param policy 策略
     * @return 按父顺序排列的子集
     */
    public static List<ToolDescriptor> derive(List<ToolDescriptor> parentTools,
            SubagentRequest request, int childDepth, SubagentPolicy policy) {
        Objects.requireNonNull(parentTools, "parentTools 不能为空");
        Objects.requireNonNull(request, "request 不能为空");
        Objects.requireNonNull(policy, "policy 不能为空");
        Set<String> allowed = Set.copyOf(request.allowedTools());
        boolean childMaySpawn = policy.allowsChildOf(childDepth);
        Map<ToolName, ToolDescriptor> selected = new LinkedHashMap<>();
        for (ToolDescriptor tool : parentTools) {
            String name = tool.name().toString();
            if (request.hasToolWhitelist() && !allowed.contains(name)) {
                continue;
            }
            if (SUBAGENT_TOOL.equals(name) && !childMaySpawn) {
                continue;
            }
            selected.put(tool.name(), tool);
        }
        return List.copyOf(selected.values());
    }

    /**
     * 判断给定深度是否还能继续派生。
     *
     * @param depth 当前会话深度
     * @param policy 策略
     * @return true 表示还能创建更深一层子代理
     */
    public static boolean canSpawnFrom(int depth, SubagentPolicy policy) {
        return policy.allowsChildOf(depth);
    }
}
