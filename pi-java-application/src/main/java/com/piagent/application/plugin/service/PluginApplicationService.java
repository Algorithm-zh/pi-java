package com.piagent.application.plugin.service;

import com.piagent.application.plugin.command.InstallPluginCommand;
import com.piagent.domain.extension.model.PluginId;
import com.piagent.domain.extension.model.PluginInstallation;
import com.piagent.domain.extension.model.PluginPackage;
import com.piagent.domain.extension.model.PluginStatus;
import com.piagent.domain.extension.port.PluginPackageInspector;
import com.piagent.domain.extension.port.PluginRepository;
import com.piagent.domain.extension.port.PluginRuntime;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 插件用例服务：安装、启停、查询与启动恢复。
 *
 * <p>数据库状态与运行时状态分开维护：安装只写记录；启用先加载运行时再置
 * ACTIVE，加载失败落库为 FAILED；停用先移出工具目录再落库。这样重启后
 * 只需恢复 ACTIVE 记录，不需要重放历史操作。</p>
 */
@Service
public class PluginApplicationService {

    private static final System.Logger LOG =
            System.getLogger(PluginApplicationService.class.getName());

    private final PluginRepository repository;
    private final PluginRuntime runtime;
    private final PluginPackageInspector inspector;
    private final Clock clock;

    /**
     * 创建插件用例服务。
     *
     * @param repository 安装记录仓储
     * @param runtime 插件运行时
     * @param inspector jar 检查器
     * @param clock 时钟
     */
    public PluginApplicationService(PluginRepository repository, PluginRuntime runtime,
            PluginPackageInspector inspector, Clock clock) {
        this.repository = repository;
        this.runtime = runtime;
        this.inspector = inspector;
        this.clock = clock;
    }

    /**
     * 安装插件，默认停在 INSTALLED，不加载代码。
     *
     * @param command 安装命令
     * @return 安装记录
     */
    @Transactional
    public PluginInstallation install(InstallPluginCommand command) {
        PluginPackage pkg = inspector.inspect(command.jarPath());
        if (repository.findById(pkg.id()).isPresent()) {
            throw new DomainException(DomainErrorCode.PLUGIN_ALREADY_EXISTS,
                    "插件已安装: " + pkg.id());
        }
        PluginInstallation installation =
                PluginInstallation.install(pkg, command.configJson(), clock.instant());
        repository.insert(installation);
        return installation;
    }

    /**
     * 启用插件。
     *
     * @param pluginId 插件标识
     * @return 启用后的记录
     */
    @Transactional
    public PluginInstallation enable(String pluginId) {
        PluginInstallation installation = require(new PluginId(pluginId));
        if (installation.status() == PluginStatus.ACTIVE) {
            return installation;
        }
        try {
            runtime.load(installation);
        } catch (RuntimeException error) {
            markFailed(installation, error);
            throw error;
        }
        installation.activate(clock.instant());
        persistEnabled(installation);
        return installation;
    }

    /**
     * 停用插件。
     *
     * @param pluginId 插件标识
     * @return 停用后的记录
     */
    @Transactional
    public PluginInstallation disable(String pluginId) {
        PluginInstallation installation = require(new PluginId(pluginId));
        if (installation.status() == PluginStatus.DISABLED) {
            return installation;
        }
        installation.disable(clock.instant());
        if (runtime.isLoaded(installation.id())) {
            runtime.unload(installation.id());
        }
        repository.update(installation);
        return installation;
    }

    /**
     * 按标识查询。
     *
     * @param pluginId 插件标识
     * @return 安装记录
     */
    @Transactional(readOnly = true)
    public PluginInstallation find(String pluginId) {
        return require(new PluginId(pluginId));
    }

    /**
     * 查询全部安装记录。
     *
     * @return 按标识排序的记录
     */
    @Transactional(readOnly = true)
    public List<PluginInstallation> list() {
        return repository.findAll();
    }

    /**
     * 启动恢复：加载所有 ACTIVE 记录，失败的标记为 FAILED。
     *
     * <p>单个插件恢复失败不阻止应用启动，也不影响其他插件。</p>
     *
     * @return 成功加载的插件数
     */
    @Transactional
    public int restoreActive() {
        int restored = 0;
        for (PluginInstallation installation : repository.findAll()) {
            if (installation.status() != PluginStatus.ACTIVE) {
                continue;
            }
            if (runtime.isLoaded(installation.id())) {
                restored++;
                continue;
            }
            try {
                runtime.load(installation);
                restored++;
            } catch (RuntimeException error) {
                LOG.log(System.Logger.Level.WARNING,
                        "恢复插件失败: " + installation.id(), error);
                installation.fail(message(error), clock.instant());
                repository.update(installation);
            }
        }
        return restored;
    }

    private void persistEnabled(PluginInstallation installation) {
        try {
            repository.update(installation);
        } catch (RuntimeException error) {
            runtime.unload(installation.id());
            throw error;
        }
    }

    private void markFailed(PluginInstallation installation, RuntimeException error) {
        installation.fail(message(error), clock.instant());
        repository.update(installation);
    }

    private String message(RuntimeException error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private PluginInstallation require(PluginId id) {
        return repository.findById(id)
                .orElseThrow(() -> new DomainException(DomainErrorCode.PLUGIN_NOT_FOUND,
                        "插件未安装: " + id));
    }
}
