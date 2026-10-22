package com.piagent.infrastructure.config;

import com.piagent.application.agent.service.ContextCompactionState;
import com.piagent.application.agent.service.SessionEntryAppender;
import com.piagent.application.context.CompactionComponents;
import com.piagent.application.context.CompactionApplicationService;
import com.piagent.application.context.CompactionSummarizer;
import com.piagent.application.context.ContextCompactionService;
import com.piagent.application.context.ContextMeasurementFactory;
import com.piagent.application.context.SkillApplicationService;
import com.piagent.domain.context.CompactionPlanner;
import com.piagent.domain.context.CompactionPromptBuilder;
import com.piagent.domain.context.CompactionSummaryFormatter;
import com.piagent.domain.context.CompactionTriggerPolicy;
import com.piagent.domain.context.EntryTokenCounter;
import com.piagent.domain.context.TokenEstimator;
import com.piagent.domain.context.port.SkillSource;
import com.piagent.domain.model.llm.LlmGateway;
import com.piagent.domain.model.port.ModelProfileRepository;
import com.piagent.domain.session.port.SessionEntryStore;
import com.piagent.domain.session.port.SessionRepository;
import com.piagent.domain.session.service.SessionContextProjector;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 应用层压缩用例的装配。
 *
 * <p>放在基础设施层的原因：{@code ContextCompactionService} 依赖纯函数式回调
 * {@code CompactionWriter} 而不是仓储接口，把“回调 = entry 追加器”这一绑定放在
 * 外层配置里，服务本身即可脱离 Spring 单测（应用层不出现 {@code @Configuration}）。</p>
 */
@Configuration
public class CompactionConfiguration {

    /**
     * token 估算器（应用层与基础设施共用同一实例，保证口径一致）。
     *
     * @return 估算器
     */
    @Bean
    public TokenEstimator tokenEstimator() {
        return new TokenEstimator();
    }

    /**
     * 摘要生成器。
     *
     * @param gateway LLM 网关
     * @param promptBuilder 请求组装器
     * @param estimator 估算器
     * @param clock 时钟
     * @return 生成器
     */
    @Bean
    public CompactionSummarizer compactionSummarizer(LlmGateway gateway,
            CompactionPromptBuilder promptBuilder, TokenEstimator estimator, Clock clock) {
        return new CompactionSummarizer(gateway, promptBuilder, estimator, clock);
    }

    /**
     * 压缩领域组件集合。
     *
     * <p>分成一个 Bean 而不是逐个注入到服务里，有两个原因：压缩服务只需要“一组纯函数
     * 组件 + 一个落库回调”，组件之间没有单独替换的需求；同时 Spring 的 Bean 方法
     * 也能保持参数精简。</p>
     *
     * @param tokenCounter token 计数器
     * @param estimator 估算器
     * @param triggerPolicy 触发策略
     * @param planner 规划器
     * @param summarizer 摘要生成器
     * @param formatter 摘要格式化器
     * @return 组件集合
     */
    @Bean
    public CompactionComponents compactionComponents(EntryTokenCounter tokenCounter,
            TokenEstimator estimator, CompactionTriggerPolicy triggerPolicy,
            CompactionPlanner planner, CompactionSummarizer summarizer,
            CompactionSummaryFormatter formatter) {
        return new CompactionComponents(tokenCounter, estimator, triggerPolicy, planner,
                summarizer, formatter);
    }

    /**
     * 压缩编排服务。
     *
     * <p>落库回调使用 {@link SessionEntryAppender#appendCompaction}：run 内压缩推进叶子、
     * 空闲会话压缩移动叶子，二者的差异由追加器根据会话状态处理。</p>
     *
     * @param components 领域组件集合
     * @param appender entry 追加器
     * @return 压缩服务
     */
    @Bean
    public ContextCompactionService contextCompactionService(CompactionComponents components,
            SessionEntryAppender appender) {
        return new ContextCompactionService(components, appender::appendCompaction);
    }

    /**
     * 上下文测量工厂。
     *
     * @param projector 投影器
     * @param compaction 压缩编排
     * @return 工厂
     */
    @Bean
    public ContextMeasurementFactory contextMeasurementFactory(
            SessionContextProjector projector, ContextCompactionService compaction) {
        return new ContextMeasurementFactory(projector, compaction);
    }

    /**
     * 技能查询用例。
     *
     * @param skillSource 技能源
     * @return 用例服务
     */
    @Bean
    public SkillApplicationService skillApplicationService(SkillSource skillSource) {
        return new SkillApplicationService(skillSource);
    }

    /**
     * run 级压缩状态。
     *
     * <p>单例 + 按 runId 分桶；run 结束时由运行时清理，因此不存在跨会话串扰。</p>
     *
     * @return 状态容器
     */
    @Bean
    public ContextCompactionState contextCompactionState() {
        return new ContextCompactionState();
    }

    /**
     * 手动压缩用例。
     *
     * @param sessions 会话仓储
     * @param entryStore entry 存储
     * @param projector 投影器
     * @param compaction 压缩编排
     * @param measurements 测量工厂
     * @param models 模型档案仓储
     * @return 用例服务
     */
    @Bean
    public CompactionApplicationService compactionApplicationService(SessionRepository sessions,
            SessionEntryStore entryStore, SessionContextProjector projector,
            ContextCompactionService compaction, ContextMeasurementFactory measurements,
            ModelProfileRepository models) {
        return new CompactionApplicationService(sessions, entryStore, projector, compaction,
                measurements, models);
    }
}
