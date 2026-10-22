package com.piagent.infrastructure.config;

import com.piagent.application.agent.service.ActiveRunRegistry;
import com.piagent.application.agent.service.ContextCompactionState;
import com.piagent.application.agent.service.RunEventHub;
import com.piagent.application.agent.service.RunRuntimeDependencies;
import com.piagent.application.agent.service.SessionEntryAppender;
import com.piagent.domain.session.port.SessionRepository;
import com.piagent.domain.shared.IdGenerator;
import com.piagent.domain.tool.port.ToolRegistry;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Agent 运行时线程池与运行时依赖装配。
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

    /**
     * 运行运行时依赖集合。
     *
     * <p>这是一个纯装配点：每个参数都是容器里已有的协作者，没有业务逻辑，因此在此处
     * 局部豁免参数个数检查；真正需要拆分的业务方法仍受 6 参数约束。</p>
     *
     * @param registry run 注册表
     * @param hub 事件中心
     * @param appender entry 追加器
     * @param toolRegistry 工具目录
     * @param compactionState 压缩状态
     * @param sessions 会话仓储
     * @param idGenerator 标识生成器
     * @param clock 时钟
     * @return 依赖集合
     */
    @Bean
    @SuppressWarnings("checkstyle:ParameterNumber")
    public RunRuntimeDependencies runRuntimeDependencies(ActiveRunRegistry registry, RunEventHub hub,
            SessionEntryAppender appender, ToolRegistry toolRegistry,
            ContextCompactionState compactionState, SessionRepository sessions,
            IdGenerator idGenerator, Clock clock) {
        return new RunRuntimeDependencies(registry, hub, appender, toolRegistry, compactionState,
                sessions, idGenerator, clock);
    }
}
