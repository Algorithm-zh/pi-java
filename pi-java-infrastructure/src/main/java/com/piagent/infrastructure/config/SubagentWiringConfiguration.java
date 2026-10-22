package com.piagent.infrastructure.config;

import com.piagent.application.subagent.SubagentExecutionService;
import com.piagent.application.subagent.SubagentService;
import com.piagent.application.subagent.SubagentSessionFactory;
import com.piagent.domain.shared.IdGenerator;
import com.piagent.domain.subagent.SubagentPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 子代理装配。
 *
 * <p>注册子代理策略、会话工厂与用例服务；工具定义由 {@link ToolConfiguration}
 * 与内置工具一起组装进运行时目录，保证“目录项 + 执行者”始终成对出现。</p>
 */
@Configuration
public class SubagentWiringConfiguration {

    /**
     * 子代理策略。
     *
     * @param maxDepth 最大深度
     * @param defaultTimeoutSeconds 默认超时
     * @param maxTimeoutSeconds 最大超时
     * @return 策略
     */
    @Bean
    public SubagentPolicy subagentPolicy(
            @Value("${pi.agent.max-subagent-depth:2}") int maxDepth,
            @Value("${pi.agent.subagent-timeout-seconds:300}") int defaultTimeoutSeconds,
            @Value("${pi.agent.subagent-max-timeout-seconds:600}") int maxTimeoutSeconds) {
        return new SubagentPolicy(maxDepth, defaultTimeoutSeconds, maxTimeoutSeconds);
    }

    /**
     * 子代理会话工厂。
     *
     * @param idGenerator 标识生成器
     * @return 工厂
     */
    @Bean
    public SubagentSessionFactory subagentSessionFactory(IdGenerator idGenerator) {
        return new SubagentSessionFactory(idGenerator);
    }

    /**
     * 子代理用例。
     *
     * @param execution 执行服务
     * @param policy 策略
     * @return 用例服务
     */
    @Bean
    public SubagentService subagentService(SubagentExecutionService execution,
            SubagentPolicy policy) {
        return new SubagentService(execution, policy);
    }
}
