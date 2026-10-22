package com.piagent.trigger.http.governance;

import com.piagent.application.governance.readmodel.ToolPolicyView;
import com.piagent.application.governance.service.ToolPolicyApplicationService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工具策略 REST 控制器。
 *
 * <p>查询返回工作区可见规则（工作区规则 + 用户全局规则）；写入按
 * {@code (scope, workspaceKey, toolName)} 幂等 upsert，存在时更新并递增
 * 版本，不存在时创建。工具是否存在由应用层校验。</p>
 */
@RestController
@RequestMapping("/api/v1/tool-policies")
public class ToolPolicyController {

    private final ToolPolicyApplicationService service;

    /**
     * 创建控制器。
     *
     * @param service 策略用例服务
     */
    public ToolPolicyController(ToolPolicyApplicationService service) {
        this.service = service;
    }

    /**
     * 查询工作区可见策略。
     *
     * @param cwd 工作目录，可选
     * @return 策略列表
     */
    @GetMapping
    public List<ToolPolicyResponse> list(
            @RequestParam(name = "cwd", required = false) String cwd) {
        return service.list(cwd).stream().map(ToolPolicyResponse::from).toList();
    }

    /**
     * 新增或更新策略。
     *
     * @param request 策略请求
     * @return 保存后的策略
     */
    @PutMapping
    public ToolPolicyResponse upsert(@RequestBody UpsertToolPolicyRequest request) {
        ToolPolicyView saved = service.upsert(request.toCommand());
        return ToolPolicyResponse.from(saved);
    }
}
