package com.piagent.infrastructure.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Agent 运行时线程池装配。
 *
 * <p>Java 21 虚拟线程适合“每个 run / 每个工具调用一个线程”的阻塞式模型：
 * 不需要调优线程数，取消与超时仍由业务层控制。run 的全局并发上限由
 * {@code RunDispatcher} 的信号量限制，工具并行度由批次规则限制。</p>
 */
@Configuration
public class AgentRuntimeConfiguration {

    /**
     * Agent Loop 执行器。
     *
     * @return 虚拟线程执行器
     */
    @Bean(name = "agentExecutor", destroyMethod = "shutdownNow")
    public ExecutorService agentExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * 工具并行执行器。
     *
     * @return 虚拟线程执行器
     */
    @Bean(name = "toolExecutor", destroyMethod = "shutdownNow")
    public ExecutorService toolExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
