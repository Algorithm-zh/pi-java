package com.piagent.trigger.http.plugin;

import com.piagent.application.plugin.command.InstallPluginCommand;
import com.piagent.application.plugin.service.PluginApplicationService;
import com.piagent.domain.extension.model.PluginInstallation;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 插件管理 REST 控制器。
 *
 * <p>只做 HTTP 契约与 DTO 转换；jar 校验、状态迁移和运行时加载全部由应用层
 * 编排。安装不会执行插件代码，只有显式 enable 才会加载。</p>
 */
@RestController
@RequestMapping("/api/v1/plugins")
public class PluginController {

    private final PluginApplicationService service;

    /**
     * 创建控制器。
     *
     * @param service 插件用例服务
     */
    public PluginController(PluginApplicationService service) {
        this.service = service;
    }

    /**
     * 查询全部安装记录。
     *
     * @return 插件列表
     */
    @GetMapping
    public List<PluginResponse> list() {
        return service.list().stream().map(PluginResponse::from).toList();
    }

    /**
     * 安装插件，初始状态为 INSTALLED。
     *
     * @param request 安装请求
     * @return 安装记录
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PluginResponse install(@RequestBody InstallPluginRequest request) {
        PluginInstallation installed = service.install(
                new InstallPluginCommand(request.path(), request.configJson()));
        return PluginResponse.from(installed);
    }

    /**
     * 启用插件。
     *
     * @param id 插件标识
     * @return 启用后的记录
     */
    @PostMapping("/{id}/enable")
    public PluginResponse enable(@PathVariable String id) {
        return PluginResponse.from(service.enable(id));
    }

    /**
     * 停用插件。
     *
     * @param id 插件标识
     * @return 停用后的记录
     */
    @PostMapping("/{id}/disable")
    public PluginResponse disable(@PathVariable String id) {
        return PluginResponse.from(service.disable(id));
    }
}
