package com.piagent.trigger.http.plugin;

import com.piagent.application.plugin.command.InstallPluginCommand;
import com.piagent.application.plugin.service.PluginApplicationService;
import com.piagent.domain.extension.model.PluginInstallation;
import com.piagent.domain.extension.model.PluginPackage;
import com.piagent.domain.extension.port.PluginPackageInspector;
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
    private final PluginPackageInspector inspector;

    /**
     * 创建控制器。
     *
     * @param service 插件用例服务
     * @param inspector 插件包检查端口，用于读取展示名称与描述
     */
    public PluginController(PluginApplicationService service, PluginPackageInspector inspector) {
        this.service = service;
        this.inspector = inspector;
    }

    /**
     * 查询全部安装记录。
     *
     * @return 插件列表
     */
    @GetMapping
    public List<PluginResponse> list() {
        return service.list().stream().map(this::respond).toList();
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
        return respond(installed);
    }

    /**
     * 启用插件。
     *
     * @param id 插件标识
     * @return 启用后的记录
     */
    @PostMapping("/{id}/enable")
    public PluginResponse enable(@PathVariable String id) {
        return respond(service.enable(id));
    }

    /**
     * 停用插件。
     *
     * @param id 插件标识
     * @return 停用后的记录
     */
    @PostMapping("/{id}/disable")
    public PluginResponse disable(@PathVariable String id) {
        return respond(service.disable(id));
    }

    private PluginResponse respond(PluginInstallation installation) {
        try {
            PluginPackage pkg = inspector.inspect(installation.artifact().jarPath());
            return PluginResponse.from(installation, pkg.name(), pkg.description());
        } catch (RuntimeException unreadable) {
            // jar 被删除或损坏时仍展示安装记录，名称回退到标识。
            return PluginResponse.from(installation, null, null);
        }
    }
}
