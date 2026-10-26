package com.piagent.application.verify;

import java.time.Duration;

/**
 * 自动验证配置。
 *
 * @param command 验证命令；为空表示关闭
 * @param timeoutSeconds 超时秒数
 * @param maxOutputChars 回写与展示的输出上限
 * @param retryOnFailure 失败后是否把输出回写一次让模型修复
 */
public record VerificationOptions(
        String command,
        int timeoutSeconds,
        int maxOutputChars,
        boolean retryOnFailure) {

    /** 默认超时秒数。 */
    public static final int DEFAULT_TIMEOUT_SECONDS = 120;

    /** 默认输出上限。 */
    public static final int DEFAULT_MAX_OUTPUT_CHARS = 6_000;

    /**
     * 规范化并校验。
     *
     * @param command 命令
     * @param timeoutSeconds 超时
     * @param maxOutputChars 输出上限
     * @param retryOnFailure 失败回写
     */
    public VerificationOptions {
        command = command == null ? "" : command.strip();
        if (timeoutSeconds < 1 || timeoutSeconds > 600) {
            throw new IllegalArgumentException("pi.verify.timeout-seconds 必须在 1 到 600 之间");
        }
        if (maxOutputChars < 500 || maxOutputChars > 60_000) {
            throw new IllegalArgumentException("pi.verify.max-output-chars 必须在 500 到 60000 之间");
        }
    }

    /**
     * 是否启用验证。
     *
     * @return true 表示配置了命令
     */
    public boolean enabled() {
        return !command.isBlank();
    }

    /**
     * 超时。
     *
     * @return 超时
     */
    public Duration timeout() {
        return Duration.ofSeconds(timeoutSeconds);
    }

    /**
     * 默认配置：未配置命令，验证关闭。
     *
     * @return 默认配置
     */
    public static VerificationOptions disabled() {
        return new VerificationOptions("", DEFAULT_TIMEOUT_SECONDS,
                DEFAULT_MAX_OUTPUT_CHARS, true);
    }
}
