package com.piagent.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Git 检查点配置（{@code pi.git}）。
 *
 * <p>使用包装类型是为了区分“未配置”和“显式关闭”：{@code pi.git.enabled} 缺省为 true，
 * 未安装 git 或工作区不是仓库时会在运行时优雅降级。</p>
 *
 * @param enabled 是否启用检查点与改动记录
 * @param timeoutSeconds 单条 git 命令的超时秒数
 * @param maxDiffChars 单个 diff 最多保留的字符数
 */
@ConfigurationProperties(prefix = "pi.git")
public record GitSettings(Boolean enabled, Integer timeoutSeconds, Integer maxDiffChars) {

    private static final int DEFAULT_TIMEOUT_SECONDS = 10;
    private static final int DEFAULT_MAX_DIFF_CHARS = 60_000;

    /**
     * 规范化默认值并校验。
     *
     * @param enabled 是否启用
     * @param timeoutSeconds 超时秒数
     * @param maxDiffChars diff 上限
     */
    public GitSettings {
        enabled = enabled == null ? Boolean.TRUE : enabled;
        timeoutSeconds = timeoutSeconds == null ? DEFAULT_TIMEOUT_SECONDS : timeoutSeconds;
        maxDiffChars = maxDiffChars == null ? DEFAULT_MAX_DIFF_CHARS : maxDiffChars;
        if (timeoutSeconds < 1) {
            throw new IllegalArgumentException("pi.git.timeout-seconds 必须大于 0");
        }
        if (maxDiffChars < 1_000) {
            throw new IllegalArgumentException("pi.git.max-diff-chars 不能小于 1000");
        }
    }

    /**
     * 默认配置，供测试与未配置场景使用。
     *
     * @return 默认值
     */
    public static GitSettings defaults() {
        return new GitSettings(true, DEFAULT_TIMEOUT_SECONDS, DEFAULT_MAX_DIFF_CHARS);
    }

    /**
     * 是否启用。
     *
     * @return true 表示启用
     */
    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }
}
