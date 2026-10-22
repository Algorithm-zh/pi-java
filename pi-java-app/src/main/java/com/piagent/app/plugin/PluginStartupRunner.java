package com.piagent.app.plugin;

import com.piagent.application.plugin.service.PluginApplicationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时恢复插件运行时。
 *
 * <p>数据库里状态为 ACTIVE 的插件在进程重启后需要重新加载。恢复失败不会
 * 阻断应用启动：应用服务会把该插件标记为 FAILED，其他插件继续加载。</p>
 */
@Component
public class PluginStartupRunner implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(PluginStartupRunner.class);

    private final PluginApplicationService plugins;

    /**
     * 创建启动恢复器。
     *
     * @param plugins 插件用例服务
     */
    public PluginStartupRunner(PluginApplicationService plugins) {
        this.plugins = plugins;
    }

    @Override
    public void run(ApplicationArguments args) {
        int restored = plugins.restoreActive();
        if (restored > 0) {
            LOG.info("已恢复 {} 个已启用插件", restored);
        }
    }
}
