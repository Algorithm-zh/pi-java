package com.piagent.infrastructure.config;

import com.piagent.domain.context.CompactionPlanner;
import com.piagent.domain.context.CompactionPromptBuilder;
import com.piagent.domain.context.CompactionSummaryFormatter;
import com.piagent.domain.context.CompactionTriggerPolicy;
import com.piagent.domain.context.EntryTokenCounter;
import com.piagent.domain.context.SkillPromptFormatter;
import com.piagent.domain.context.port.SkillSource;
import com.piagent.domain.context.port.WorkspacePromptSource;
import com.piagent.infrastructure.context.AgentsDocumentSource;
import com.piagent.infrastructure.context.FileSystemSkillSource;
import com.piagent.infrastructure.context.SkillDirectoryResolver;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 上下文压缩与技能装配。
 *
 * <p>领域服务全部无状态，直接 new；两个端口分别落到文件系统实现。
 * skill 目录列表来自 {@code pi.agent.skills}，解析失败的条目跳过并记录告警，
 * 不让一条错误配置阻止应用启动。</p>
 */
@Configuration
@EnableConfigurationProperties(AgentSettings.class)
public class ContextConfiguration {

    private static final System.Logger LOG = System.getLogger(ContextConfiguration.class.getName());

    /**
     * token 估算器。
     *
     * @return 估算器
     */
    @Bean
    public EntryTokenCounter entryTokenCounter() {
        return new EntryTokenCounter();
    }

    /**
     * 压缩规划器。
     *
     * @return 规划器
     */
    @Bean
    public CompactionPlanner compactionPlanner() {
        return new CompactionPlanner();
    }

    /**
     * 压缩请求组装器。
     *
     * @return 组装器
     */
    @Bean
    public CompactionPromptBuilder compactionPromptBuilder() {
        return new CompactionPromptBuilder();
    }

    /**
     * 压缩摘要格式化器。
     *
     * @return 格式化器
     */
    @Bean
    public CompactionSummaryFormatter compactionSummaryFormatter() {
        return new CompactionSummaryFormatter();
    }

    /**
     * 压缩触发策略。
     *
     * @param settings 运行时配置
     * @return 策略
     */
    @Bean
    public CompactionTriggerPolicy compactionTriggerPolicy(AgentSettings settings) {
        return new CompactionTriggerPolicy(settings.context().minGrowthTokens());
    }

    /**
     * skill prompt 格式化器。
     *
     * @return 格式化器
     */
    @Bean
    public SkillPromptFormatter skillPromptFormatter() {
        return new SkillPromptFormatter();
    }

    /**
     * 文件系统 skill 源。
     *
     * @param settings 运行时配置
     * @param clock 时钟
     * @return skill 源
     */
    @Bean
    public SkillSource skillSource(AgentSettings settings, Clock clock) {
        List<Path> roots = settings.skills().stream()
                .map(SkillDirectoryResolver::expand)
                .filter(Objects::nonNull)
                .toList();
        LOG.log(System.Logger.Level.INFO, "已加载 {0} 个 skill 根目录: {1}",
                roots.size(), roots);
        return new FileSystemSkillSource(roots, clock);
    }

    /**
     * 工作区 AGENTS.md 读取器。
     *
     * @return 端口实现
     */
    @Bean
    public WorkspacePromptSource workspacePromptSource() {
        return new AgentsDocumentSource();
    }
}
