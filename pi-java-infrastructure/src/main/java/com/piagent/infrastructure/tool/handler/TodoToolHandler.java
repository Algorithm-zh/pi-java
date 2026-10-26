package com.piagent.infrastructure.tool.handler;

import com.piagent.application.plan.SessionPlanService;
import com.piagent.domain.plan.model.Plan;
import com.piagent.domain.plan.model.PlanItem;
import com.piagent.domain.plan.model.PlanItemStatus;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.tool.model.ToolInvocation;
import com.piagent.domain.tool.model.ToolResult;
import com.piagent.domain.tool.service.ToolArgumentGuard;
import com.piagent.infrastructure.tool.BuiltInToolHandler;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code todo} 工具：模型用它维护当前任务的待办清单。
 *
 * <p>每次调用传入完整清单（全量替换），而不是增量增删：模型不需要记住内部
 * 编号，分支回退与重启后也只依赖最后一条快照。清单最多
 * {@value Plan#MAX_ITEMS} 条，同时最多一条“进行中”。</p>
 *
 * <p>计划不写文件，因此是低风险、顺序执行的内部工具；它只在 run 内可用。</p>
 */
public final class TodoToolHandler implements BuiltInToolHandler {

    private static final Set<String> ALLOWED_ARGUMENTS = Set.of("todos");
    private static final Set<String> ITEM_ARGUMENTS = Set.of("content", "status");
    private static final int MAX_CONTENT_LENGTH = 400;

    private final SessionPlanService plans;

    /**
     * 创建工具。
     *
     * @param plans 计划服务
     */
    public TodoToolHandler(SessionPlanService plans) {
        this.plans = plans;
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        if (invocation.originSessionId() == null) {
            throw new DomainException(DomainErrorCode.TOOL_EXECUTION_FAILED,
                    "todo 只能在会话 run 中使用");
        }
        ToolArgumentGuard.rejectUnknown(invocation.arguments(), ALLOWED_ARGUMENTS);
        Plan plan = parsePlan(invocation.arguments().get("todos"));
        plans.replace(invocation.originSessionId(), plan);
        return ToolResult.success(invocation.callId(), invocation.tool(), render(plan),
                details(plan));
    }

    private Plan parsePlan(Object raw) {
        if (!(raw instanceof List<?> list)) {
            throw invalid("参数 todos 必须是数组");
        }
        if (list.size() > Plan.MAX_ITEMS) {
            throw invalid("待办不能超过 " + Plan.MAX_ITEMS + " 条");
        }
        List<PlanItem> items = new ArrayList<>(list.size());
        for (int index = 0; index < list.size(); index++) {
            items.add(parseItem(list.get(index), index));
        }
        return new Plan(items);
    }

    private PlanItem parseItem(Object raw, int index) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw invalid("待办第 " + (index + 1) + " 条必须是对象");
        }
        Map<String, Object> item = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw invalid("待办字段名必须是字符串");
            }
            if (!ITEM_ARGUMENTS.contains(key)) {
                throw invalid("待办不支持字段: " + key);
            }
            item.put(key, entry.getValue());
        }
        String content = ToolArgumentGuard.requireString(item, "content", MAX_CONTENT_LENGTH);
        String status = ToolArgumentGuard.requireString(item, "status", 32);
        try {
            return new PlanItem(content, PlanItemStatus.fromWire(status));
        } catch (IllegalArgumentException error) {
            throw invalid("待办状态只能是 pending、in_progress 或 done");
        }
    }

    private String render(Plan plan) {
        if (plan.isEmpty()) {
            return "已清空待办计划。";
        }
        StringBuilder text = new StringBuilder("待办已更新（")
                .append(plan.completedCount()).append('/').append(plan.items().size())
                .append(" 完成）");
        plan.currentIndex().ifPresent(index -> text.append("，当前：")
                .append(plan.items().get(index).content()));
        text.append('\n');
        for (PlanItem item : plan.items()) {
            text.append(marker(item.status())).append(' ').append(item.content()).append('\n');
        }
        return text.toString().stripTrailing();
    }

    private String marker(PlanItemStatus status) {
        return switch (status) {
            case DONE -> "[x]";
            case IN_PROGRESS -> "[>]";
            case PENDING -> "[ ]";
        };
    }

    private Map<String, Object> details(Plan plan) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("total", plan.items().size());
        details.put("completed", plan.completedCount());
        details.put("items", plan.items().stream().map(item -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("content", item.content());
            value.put("status", item.status().wireName());
            return value;
        }).toList());
        return details;
    }

    private DomainException invalid(String message) {
        return new DomainException(DomainErrorCode.TOOL_INVALID_ARGUMENT, message);
    }
}
