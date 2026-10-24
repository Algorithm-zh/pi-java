package com.piagent.trigger.cli;

import org.springframework.context.ApplicationContext;
import picocli.CommandLine;

/**
 * 让 picocli 从 Spring 容器解析命令实例。
 *
 * <p>picocli 默认用反射创建子命令，那样会绕过构造器注入；这里改为按类型取
 * Spring bean，保证命令拿到的是与 Web 完全相同的用例服务。</p>
 */
final class SpringCliFactory implements CommandLine.IFactory {

    private final ApplicationContext context;

    SpringCliFactory(ApplicationContext context) {
        this.context = context;
    }

    @Override
    public <K> K create(Class<K> cls) {
        return context.getBean(cls);
    }
}
