package com.piagent.infrastructure.config;

import com.piagent.domain.governance.service.PathSandbox;
import com.piagent.domain.tool.port.ToolInvoker;
import com.piagent.infrastructure.plugin.PluginHost;
import com.piagent.infrastructure.plugin.RuntimeToolInvoker;
import com.piagent.infrastructure.plugin.RuntimeToolRegistry;
import com.piagent.infrastructure.tool.BuiltInToolCatalog;
import com.piagent.infrastructure.tool.BuiltInToolHandlers;
import com.piagent.infrastructure.tool.BuiltInToolInvoker;
import com.piagent.infrastructure.tool.RealPathResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 内置工具装配。
 *
 * <p>工具目录与执行体分离：目录是可被模型看到的不可变定义，执行体持有
 * 路径解析器。只读工具可并行；写文件与 shell 工具顺序执行，并由策略层
 * 决定是否需要人工审批。</p>
 */
@Configuration
public class ToolConfiguration {

    /**
     * 纯路径沙箱。
     *
     * @return 沙箱
     */
    @Bean
    public PathSandbox pathSandbox() {
        return new PathSandbox();
    }

    /**
     * 真实路径解析器。
     *
     * @param sandbox 纯路径沙箱
     * @return 解析器
     */
    @Bean
    public RealPathResolver realPathResolver(PathSandbox sandbox) {
        return new RealPathResolver(sandbox);
    }

    /**
     * 运行时工具注册表：内置工具 + 动态插件工具。
     *
     * @return 注册表
     */
    @Bean
    public RuntimeToolRegistry toolRegistry() {
        return new RuntimeToolRegistry(BuiltInToolCatalog.descriptors());
    }

    /**
     * 内置工具调用器。
     *
     * @param registry 工具注册表
     * @param resolver 路径解析器
     * @return 调用器
     */
    @Bean(destroyMethod = "close")
    public BuiltInToolInvoker builtInToolInvoker(RuntimeToolRegistry registry,
            RealPathResolver resolver) {
        return new BuiltInToolInvoker(registry, BuiltInToolHandlers.create(resolver));
    }

    /**
     * 统一工具调用入口：插件优先，其余走内置实现。
     *
     * @param builtIn 内置调用器
     * @param plugins 插件宿主
     * @return 调用器
     */
    @Bean
    @Primary
    public ToolInvoker toolInvoker(BuiltInToolInvoker builtIn, PluginHost plugins) {
        return new RuntimeToolInvoker(builtIn, plugins);
    }
}
