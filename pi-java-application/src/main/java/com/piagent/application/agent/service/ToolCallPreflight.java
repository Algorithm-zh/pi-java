package com.piagent.application.agent.service;

import com.piagent.domain.governance.model.PolicyEffect;
import com.piagent.domain.governance.model.ToolPolicyRule;
import com.piagent.domain.governance.model.WorkspaceKey;
import com.piagent.domain.governance.port.ToolPolicyRepository;
import com.piagent.domain.governance.service.ToolAccessPolicy;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.message.ToolCallBlock;
import com.piagent.domain.tool.model.ExecutionMode;
import com.piagent.domain.tool.model.ToolCallId;
import com.piagent.domain.tool.model.ToolDescriptor;
import com.piagent.domain.tool.model.ToolName;
import com.piagent.domain.tool.model.ToolResult;
import com.piagent.domain.tool.port.ToolRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 工具调用预检：解析策略、校验注册状态并应用预算。
 *
 * <p>预检只产生“执行计划”，不调用工具；把这段逻辑从协调器中拆出，
 * 让协调器专注执行顺序、审批等待与结果回写。每个调用无论被放行、拒绝
 * 还是待审批，都会得到一条可回写的 {@link PreparedCall}。</p>
 */
@Component
public class ToolCallPreflight {

    private final ToolPolicyRepository policyRepository;
    private final ToolAccessPolicy accessPolicy;

    /**
     * 创建预检器。
     *
     * @param policyRepository 策略仓储
     * @param accessPolicy 策略解析器
     */
    public ToolCallPreflight(ToolPolicyRepository policyRepository,
            ToolAccessPolicy accessPolicy) {
        this.policyRepository = policyRepository;
        this.accessPolicy = accessPolicy;
    }

    /**
     * 预检一批调用并应用剩余预算。
     *
     * @param session 当前会话
     * @param calls 模型返回的调用
     * @param registry 本次 run 的工具快照
     * @param remainingCalls 剩余可执行次数
     * @return 按调用顺序排列的执行计划
     */
    public List<PreparedCall> prepare(ChatSession session, List<ToolCallBlock> calls,
            ToolRegistry registry, int remainingCalls) {
        List<ToolPolicyRule> rules = policyRepository.findByWorkspace(
                WorkspaceKey.of(session.config().cwd()));
        List<PreparedCall> prepared = new ArrayList<>(calls.size());
        int granted = 0;
        for (ToolCallBlock call : calls) {
            PreparedCall preflight = preflight(call, registry, rules);
            if (preflight.requiresExecution() && granted >= remainingCalls) {
                preflight = preflight.withRejection(ToolResult.error(callIdOf(call), toolNameOf(call),
                        "已达到本次运行的工具调用上限，该调用未执行"), true);
            } else if (preflight.requiresExecution()) {
                granted++;
            }
            prepared.add(preflight);
        }
        return prepared;
    }

    /**
     * 判断整批调用是否可以并行执行。
     *
     * <p>混入任一待审批调用就不并行，避免多个审批弹窗同时出现；
     * 串行工具同样会把整批降级为顺序执行。</p>
     *
     * @param prepared 执行计划
     * @return true 表示全部可并行
     */
    public boolean supportsParallel(List<PreparedCall> prepared) {
        if (prepared.stream().anyMatch(PreparedCall::approvalRequired)) {
            return false;
        }
        List<PreparedCall> executable = prepared.stream().filter(PreparedCall::executable).toList();
        return executable.size() > 1 && executable.stream()
                .allMatch(call -> call.descriptor().executionMode() == ExecutionMode.PARALLEL);
    }

    private PreparedCall preflight(ToolCallBlock call, ToolRegistry registry,
            List<ToolPolicyRule> rules) {
        ToolName name = toolNameOf(call);
        Optional<ToolDescriptor> found = registry.find(name);
        if (found.isEmpty()) {
            return new PreparedCall(call, null, ToolResult.error(callIdOf(call), name,
                    "工具未注册: " + call.name()), false, false);
        }
        ToolDescriptor descriptor = found.get();
        PolicyEffect effect = accessPolicy.resolve(descriptor, rules);
        return switch (effect) {
            case ALLOW -> new PreparedCall(call, descriptor, null, false, false);
            case ASK -> new PreparedCall(call, descriptor, null, true, false);
            case DENY -> new PreparedCall(call, descriptor, ToolResult.error(callIdOf(call), name,
                    "工具策略拒绝执行: " + name), false, false);
        };
    }

    private ToolCallId callIdOf(ToolCallBlock call) {
        return new ToolCallId(call.id());
    }

    private ToolName toolNameOf(ToolCallBlock call) {
        return new ToolName(call.name());
    }
}
