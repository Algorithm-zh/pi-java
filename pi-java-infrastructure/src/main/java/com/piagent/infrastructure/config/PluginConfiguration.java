package com.piagent.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.piagent.infrastructure.plugin.PluginHost;
import com.piagent.infrastructure.plugin.PluginJarInspector;
import com.piagent.infrastructure.plugin.RuntimeToolRegistry;
import java.util.concurrent.ExecutorService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 插件运行时装配。
 *
 * <p>插件宿主是有状态组件：持有已加载插件、类加载器与工具句柄，因此由容器
 * 单例管理并在关闭时统一释放。插件工具复用 {@code toolExecutor} 虚拟线程池，
 * 与内置工具共享同一条超时与取消路径。</p>
 */
@Configuration
public class PluginConfiguration {

    /**
     * 插件 jar 检查器。
     *
     * @param mapper JSON 映射器
     * @return 检查器
     */
    @Bean
    public PluginJarInspector pluginJarInspector(ObjectMapper mapper) {
        return new PluginJarInspector(mapper);
    }

    /**
     * 插件宿主。
     *
     * @param inspector jar 检查器
     * @param registry 运行时工具目录
     * @param mapper JSON 映射器
     * @param executor 工具执行器
     * @return 宿主
     */
    @Bean(destroyMethod = "close")
    public PluginHost pluginHost(PluginJarInspector inspector, RuntimeToolRegistry registry,
            ObjectMapper mapper, @Qualifier("toolExecutor") ExecutorService executor) {
        return new PluginHost(inspector, registry, mapper, executor);
    }
}
