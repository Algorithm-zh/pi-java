package com.piagent.trigger.http.governance;

import com.piagent.application.governance.service.ApprovalApplicationService;
import com.piagent.domain.governance.model.ApprovalId;
import com.piagent.domain.governance.model.ApprovalRequest;
import com.piagent.domain.governance.model.ApprovalStatus;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审批 REST 控制器。
 *
 * <p>前端通过会话 ID 拉取待办，用于页面刷新或 SSE 断线后的兜底恢复；
 * 决策接口返回终态审批，SSE 仍会另行广播 {@code approval.resolved}，
 * 两条通道都以数据库状态为准。</p>
 *
 * <p>v0.1 没有登录体系，决策者固定为 {@value #LOCAL_DECIDER}，客户端
 * 不能指定，避免界面伪造审计身份。</p>
 */
@RestController
@RequestMapping("/api/v1/approvals")
public class ApprovalController {

    private static final String LOCAL_DECIDER = "local-user";

    private final ApprovalApplicationService service;

    /**
     * 创建控制器。
     *
     * @param service 审批用例服务
     */
    public ApprovalController(ApprovalApplicationService service) {
        this.service = service;
    }

    /**
     * 查询会话下的审批。
     *
     * @param sessionId 会话标识，必填
     * @param status 状态过滤，可选
     * @return 审批列表
     */
    @GetMapping
    public List<ApprovalResponse> list(
            @RequestParam(name = "sessionId", required = false) String sessionId,
            @RequestParam(name = "status", required = false) String status) {
        String raw = Guard.notBlank(sessionId, DomainErrorCode.INVALID_ARGUMENT,
                "sessionId 不能为空");
        ApprovalStatus filter = status == null || status.isBlank()
                ? null
                : EnumParse.parse(ApprovalStatus.class, status, "status");
        return service.findBySession(SessionId.parse(raw), filter).stream()
                .map(ApprovalResponse::from)
                .toList();
    }

    /**
     * 查询单个审批。
     *
     * @param id 审批标识
     * @return 审批详情
     */
    @GetMapping("/{id}")
    public ApprovalResponse find(@PathVariable String id) {
        return ApprovalResponse.from(service.find(ApprovalId.parse(id)));
    }

    /**
     * 提交审批决策。
     *
     * @param id 审批标识
     * @param request 决策请求
     * @return 终态审批
     */
    @PostMapping("/{id}/decision")
    public ApprovalResponse decide(@PathVariable String id,
            @RequestBody DecideApprovalRequest request) {
        ApprovalRequest resolved = service.decide(ApprovalId.parse(id),
                request.toDecision(), LOCAL_DECIDER, request.reason());
        return ApprovalResponse.from(resolved);
    }
}
