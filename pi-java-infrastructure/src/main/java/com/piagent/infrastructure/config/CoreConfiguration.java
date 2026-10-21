package com.piagent.infrastructure.config;

import com.piagent.domain.session.service.SessionBranchPolicy;
import com.piagent.domain.session.service.SessionContextProjector;
import com.piagent.domain.shared.IdGenerator;
import com.piagent.domain.shared.Ulid;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 基础设施层通用 Bean：时钟、标识生成器与无状态领域服务。
 *
 * <p>Clock 统一注入 UTC，避免宿主机时区影响 session/entry 的时间排序。
 */
@Configuration
public class CoreConfiguration {

    /**
     * 系统 UTC 时钟。
     *
     * @return 时钟
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * ULID 生成器，与时钟共享同一时间源。
     *
     * @param clock 时钟
     * @return 标识生成器
     */
    @Bean
    public IdGenerator idGenerator(Clock clock) {
        return () -> Ulid.generate(clock.instant()).value();
    }

    /**
     * 会话上下文投影器。
     *
     * @return 投影器
     */
    @Bean
    public SessionContextProjector sessionContextProjector() {
        return new SessionContextProjector();
    }

    /**
     * 分支移动策略。
     *
     * @return 策略
     */
    @Bean
    public SessionBranchPolicy sessionBranchPolicy() {
        return new SessionBranchPolicy();
    }
}
