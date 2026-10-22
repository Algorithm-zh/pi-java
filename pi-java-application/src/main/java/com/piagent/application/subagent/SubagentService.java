package com.piagent.application.subagent;

import com.piagent.domain.agent.RunStopReason;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.TokenUsage;
import com.piagent.domain.subagent.SubagentPolicy;
import com.piagent.domain.subagent.SubagentRequest;
import com.piagent.domain.subagent.SubagentResult;
import com.piagent.domain.subagent.SubagentToolset;
import com.piagent.domain.tool.model.ToolDescriptor;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * 子代理用例服务：把一次 {@code subagent} 工具调用转成一次同步的子 run。
 *
 * <p>流程：</p>
 * <pre>
 * 校验深度 → 计算工具子集 → 创建子会话 → 组装任务提示 → 启动并等待 → 结构化结果
 * </pre>
 *
 * <p>关键约束：</p>
 * <ul>
 *   <li><b>失败不抛给父循环</b>：任何异常都转成 {@link SubagentResult}（{@code ERROR}），
 *       父模型永远能拿到一条 toolResult；</li>
 *   <li><b>工具子集只收紧</b>：见 {@link SubagentToolset}；</li>
 *   <li><b>深度超限</b>：直接返回错误结果，而不是静默降级为普通对话。</li>
 * </ul>
 */
@Service
public class SubagentService {

    private static final System.Logger LOG = System.getLogger(SubagentService.class.getName());

    private final SubagentExecutionService execution;
    private final SubagentPolicy policy;

    /**
     * 创建用例服务。
     *
     * @param execution 执行服务
     * @param policy 子代理策略
     */
    public SubagentService(SubagentExecutionService execution, SubagentPolicy policy) {
        this.execution = Objects.requireNonNull(execution, "execution 不能为空");
        this.policy = Objects.requireNonNull(policy, "policy 不能为空");
    }

    /**
     * 当前的深度策略。
     *
     * @return 策略
     */
    public SubagentPolicy policy() {
        return policy;
    }

    /**
     * 计算本次子代理可用的工具子集。
     *
     * @param parentTools 父 run 的工具快照
     * @param parent 父会话
     * @param request 请求
     * @return 子集
     */
    public List<ToolDescriptor> toolsFor(List<ToolDescriptor> parentTools, ChatSession parent,
            SubagentRequest request) {
        int childDepth = parent.identity().depth() + 1;
        return SubagentToolset.derive(parentTools, request, childDepth, policy);
    }

    /**
     * 深度是否还允许派生子代理。
     *
     * @param parent 父会话
     * @return true 表示允许
     */
    public boolean canSpawn(ChatSession parent) {
        return SubagentToolset.canSpawnFrom(parent.identity().depth(), policy);
    }

    /**
     * 执行一次子代理。
     *
     * @param parent 父会话
     * @param request 请求
     * @param parentTools 父 run 的工具快照
     * @return 结构化结果；失败时 {@code stopReason = ERROR}
     */
    public SubagentResult delegate(ChatSession parent, SubagentRequest request,
            List<ToolDescriptor> parentTools) {
        if (!canSpawn(parent)) {
            return failure(parent, "子代理深度已达上限（" + policy.maxDepth()
                    + "），不能再派生新的子代理");
        }
        List<ToolDescriptor> childTools = toolsFor(parentTools, parent, request);
        if (childTools.isEmpty()) {
            return failure(parent, "工具白名单与父工具集没有交集，子代理无法执行任何工具");
        }
        int timeoutSeconds = policy.resolveTimeoutSeconds(request.timeoutSeconds());
        ChatSession child;
        try {
            child = execution.prepare(parent, request);
        } catch (RuntimeException error) {
            LOG.log(System.Logger.Level.WARNING, "创建子代理会话失败", error);
            return failure(parent, "创建子代理会话失败: " + message(error));
        }
        try {
            String prompt = promptOf(request, childTools);
            return execution.run(child, prompt, Duration.ofSeconds(timeoutSeconds));
        } catch (RuntimeException error) {
            LOG.log(System.Logger.Level.WARNING, "子代理执行失败: " + child.identity().id(), error);
            return new SubagentResult(child.identity().id(), RunStopReason.ERROR,
                    "子代理执行失败: " + message(error), TokenUsage.zero(), 0, 0, 0);
        }
    }

    /**
     * 组装子代理的首条用户消息。
     *
     * <p>包含三部分：角色（可选）、任务本身、可用的工具清单，以及输出契约。
     * 子代理的中间过程不会回传给父模型，明确“只输出结论”能显著减少父上下文膨胀。</p>
     *
     * @param request 请求
     * @param tools 子代理可用工具
     * @return 提示词
     */
    String promptOf(SubagentRequest request, List<ToolDescriptor> tools) {
        StringBuilder prompt = new StringBuilder();
        if (request.persona() != null) {
            prompt.append("角色：").append(request.persona()).append("\n\n");
        }
        prompt.append("任务：\n").append(request.task().strip()).append("\n\n");
        prompt.append("你可以使用这些工具：")
                .append(tools.stream().map(tool -> tool.name().toString()).toList())
                .append("\n\n");
        prompt.append("""
                输出要求：
                1. 先用工具完成任务，再给出结论；
                2. 只输出最终结论，不要复述你的每一次工具调用；
                3. 结论要包含关键证据（文件路径、行号、命令输出要点），便于主代理直接采信；
                4. 无法完成时明确说明卡在哪里，不要编造结果。
                """);
        return prompt.toString();
    }

    /**
     * 父级取消时终止子 run。
     *
     * @param child 子会话
     */
    public void cancel(ChatSession child) {
        execution.cancel(child);
    }

    private SubagentResult failure(ChatSession parent, String message) {
        return new SubagentResult(parent.identity().id(), RunStopReason.ERROR, message,
                TokenUsage.zero(), 0, 0, 0);
    }

    private String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
