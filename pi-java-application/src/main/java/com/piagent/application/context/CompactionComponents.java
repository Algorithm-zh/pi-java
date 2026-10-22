package com.piagent.application.context;

import com.piagent.domain.context.CompactionPlanner;
import com.piagent.domain.context.CompactionSummaryFormatter;
import com.piagent.domain.context.CompactionTriggerPolicy;
import com.piagent.domain.context.EntryTokenCounter;
import com.piagent.domain.context.TokenEstimator;
import java.util.Objects;

/**
 * 压缩编排所需的领域组件集合。
 *
 * <p>压缩服务需要 6 个纯函数组件 + 1 个落库回调，直接写成构造参数会超过项目约定的
 * 参数上限，也难以辨认谁负责什么。用 record 分组后，构造器只剩“组件 + 落库”两项，
 * 语义更清楚。</p>
 *
 * @param tokenCounter token 计数器
 * @param estimator 估算器
 * @param triggerPolicy 触发策略
 * @param planner 规划器
 * @param summarizer 摘要器
 * @param formatter 摘要格式化器
 */
public record CompactionComponents(
        EntryTokenCounter tokenCounter,
        TokenEstimator estimator,
        CompactionTriggerPolicy triggerPolicy,
        CompactionPlanner planner,
        CompactionSummarizer summarizer,
        CompactionSummaryFormatter formatter) {

    /**
     * 校验组件完整性。
     *
     * @param tokenCounter token 计数器
     * @param estimator 估算器
     * @param triggerPolicy 触发策略
     * @param planner 规划器
     * @param summarizer 摘要器
     * @param formatter 摘要格式化器
     */
    public CompactionComponents {
        Objects.requireNonNull(tokenCounter, "tokenCounter 不能为空");
        Objects.requireNonNull(estimator, "estimator 不能为空");
        Objects.requireNonNull(triggerPolicy, "triggerPolicy 不能为空");
        Objects.requireNonNull(planner, "planner 不能为空");
        Objects.requireNonNull(summarizer, "summarizer 不能为空");
        Objects.requireNonNull(formatter, "formatter 不能为空");
    }
}
