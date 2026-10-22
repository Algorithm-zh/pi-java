package com.piagent.application.agent.service;

import com.piagent.domain.session.port.SessionRepository;
import com.piagent.domain.shared.IdGenerator;
import com.piagent.domain.tool.port.ToolRegistry;
import java.time.Clock;
import java.util.Objects;

/**
 * 运行运行时依赖集合。
 *
 * <p>运行时需要多个协作者，直接写成构造参数会超过项目参数上限；分组后构造器只接收
 * 一个值对象，测试装配时也能一眼看出“运行时依赖哪些东西”。</p>
 *
 * @param registry run 注册表
 * @param hub 事件中心
 * @param appender entry 追加器
 * @param toolRegistry 工具目录（用于冻结 run 级快照）
 * @param compactionState run 级压缩状态
 * @param sessions 会话仓储（供溢出重试时重新读取快照）
 * @param idGenerator 标识生成器
 * @param clock 时钟
 */
public record RunRuntimeDependencies(
        ActiveRunRegistry registry,
        RunEventHub hub,
        SessionEntryAppender appender,
        ToolRegistry toolRegistry,
        ContextCompactionState compactionState,
        SessionRepository sessions,
        IdGenerator idGenerator,
        Clock clock) {

    /**
     * 校验依赖。
     *
     * @param registry run 注册表
     * @param hub 事件中心
     * @param appender entry 追加器
     * @param toolRegistry 工具目录
     * @param compactionState 压缩状态
     * @param sessions 会话仓储
     * @param idGenerator 标识生成器
     * @param clock 时钟
     */
    public RunRuntimeDependencies {
        Objects.requireNonNull(registry, "registry 不能为空");
        Objects.requireNonNull(hub, "hub 不能为空");
        Objects.requireNonNull(appender, "appender 不能为空");
        Objects.requireNonNull(toolRegistry, "toolRegistry 不能为空");
        Objects.requireNonNull(compactionState, "compactionState 不能为空");
        Objects.requireNonNull(sessions, "sessions 不能为空");
        Objects.requireNonNull(idGenerator, "idGenerator 不能为空");
        Objects.requireNonNull(clock, "clock 不能为空");
    }
}
