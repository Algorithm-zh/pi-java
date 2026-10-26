package com.piagent.trigger.http.session;

import com.piagent.application.plan.SessionPlanService;
import com.piagent.domain.session.model.SessionId;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话计划控制器：只读查询当前待办与最近一次自动验证。
 *
 * <p>写操作只允许模型通过 {@code todo} 工具完成，HTTP 面不暴露“人工改计划”的入口，
 * 避免出现两份互相覆盖的计划来源。</p>
 */
@RestController
@RequestMapping("/api/v1/sessions")
public class SessionPlanController {

    private final SessionPlanService plans;

    /**
     * 创建控制器。
     *
     * @param plans 计划用例
     */
    public SessionPlanController(SessionPlanService plans) {
        this.plans = plans;
    }

    /**
     * 查询当前计划。
     *
     * @param id 会话 ID
     * @return 计划与最近验证
     */
    @GetMapping("/{id}/plan")
    public SessionPlanResponse plan(@PathVariable String id) {
        return SessionPlanResponse.from(plans.snapshot(SessionId.parse(id)));
    }
}
