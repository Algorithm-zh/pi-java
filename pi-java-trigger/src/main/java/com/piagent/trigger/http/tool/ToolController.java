package com.piagent.trigger.http.tool;

import com.piagent.application.tool.service.ToolCatalogApplicationService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工具目录 REST 控制器。
 *
 * <p>只读接口：返回内置工具定义与当前工作区解析出的访问效果。
 * 工作区用查询参数 {@code cwd} 指定，缺省为服务进程当前目录。</p>
 */
@RestController
@RequestMapping("/api/v1/tools")
public class ToolController {

    private final ToolCatalogApplicationService service;

    /**
     * 创建控制器。
     *
     * @param service 工具目录用例服务
     */
    public ToolController(ToolCatalogApplicationService service) {
        this.service = service;
    }

    /**
     * 查询工具目录。
     *
     * @param cwd 工作目录
     * @return 工具列表
     */
    @GetMapping
    public List<ToolResponse> list(@RequestParam(name = "cwd", required = false) String cwd) {
        return service.list(cwd).stream().map(ToolResponse::from).toList();
    }
}
