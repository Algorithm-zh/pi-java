package com.piagent.infrastructure.config;

import com.piagent.application.subagent.SubagentRegistry;
import com.piagent.domain.governance.service.PathSandbox;
import com.piagent.domain.tool.model.ToolDescriptor;
import com.piagent.domain.tool.port.ToolInvoker;
import com.piagent.infrastructure.plugin.PluginHost;
import com.piagent.infrastructure.plugin.RuntimeToolInvoker;
import com.piagent.infrastructure.plugin.RuntimeToolRegistry;
import com.piagent.infrastructure.tool.BuiltInToolCatalog;
import com.piagent.infrastructure.tool.BuiltInToolHandlers;
import com.piagent.infrastructure.tool.BuiltInToolInvoker;
import com.piagent.infrastructure.tool.RealPathResolver;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
     * 运行时工具注册表：内置工具 + 子代理工具 + 动态插件工具。
     *
     * <p>子代理工具的定义来自应用层 {@code SubagentRegistry}，与内置目录合并后再交给
     * 注册表；它的执行者由应用层 {@code SubagentToolInvoker} 接管，基础设施不需要知道。</p>
     *
     * @return 注册表
     */
    @Bean
    public RuntimeToolRegistry toolRegistry() {
        List<ToolDescriptor> descriptors = new ArrayList<>(BuiltInToolCatalog.descriptors());
        descriptors.add(SubagentRegistry.descriptor());
        return new RuntimeToolRegistry(descriptors);
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
     * <p>不再标注 {@code @Primary}：应用层的 {@code SubagentToolInvoker} 才是最终注入点，
     * 它会在调用真正的执行器之前截获 {@code subagent} 并转发其余工具。这里保留具体类型，
     * 便于子代理调用器直接依赖它而不产生循环。</p>
     *
     * @param builtIn 内置调用器
     * @param plugins 插件宿主
     * @return 调用器
     */
    @Bean
    public ToolInvoker runtimeToolInvoker(BuiltInToolInvoker builtIn, PluginHost plugins) {
        return new RuntimeToolInvoker(builtIn, plugins);
    }
}
