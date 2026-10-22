package com.piagent.infrastructure.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 运行时上下文与技能配置（{@code pi.agent.context} / {@code pi.agent.skills}）。
 *
 * <p>全部字段都有安全默认值，保证“零配置启动”：没有配置 skills 目录时目录为空，
 * 压缩阈值使用文档中的默认值。</p>
 *
 * <p>注意：{@code pi.agent} 下还有 M4 已使用的 {@code max-parallelism} 等键，
 * 绑定器会忽略这里没有声明的属性，因此可以只映射关心的子集。</p>
 *
 * @param context 上下文与压缩参数
 * @param skills skill 根目录列表，按优先级从高到低
 */
@ConfigurationProperties(prefix = "pi.agent")
public record AgentSettings(Context context, List<String> skills) {

    /**
     * 规范化配置：null 集合替换为空列表。
     *
     * @param context 上下文配置
     * @param skills skill 目录
     */
    public AgentSettings {
        context = context == null ? Context.defaults() : context;
        skills = skills == null ? List.of() : List.copyOf(skills);
    }

    /**
     * 保底默认值，供测试与未配置场景使用。
     *
     * @return 默认配置
     */
    public static AgentSettings defaults() {
        return new AgentSettings(Context.defaults(), List.of());
    }

    /**
     * 上下文与压缩参数。
     *
     * @param contextWindow 兜底上下文窗口
     * @param reserveTokens 预留 token
     * @param keepRecentTokens 保留近期 token
     * @param minGrowthTokens 最小新增 token
     */
    public record Context(int contextWindow, int reserveTokens, int keepRecentTokens,
            int minGrowthTokens) {

        /**
         * 文档约定的默认值。
         *
         * @return 默认配置
         */
        public static Context defaults() {
            return new Context(128_000, 16_384, 20_000, 2_000);
        }
    }
}

