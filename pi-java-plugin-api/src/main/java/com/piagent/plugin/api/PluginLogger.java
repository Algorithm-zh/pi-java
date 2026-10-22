package com.piagent.plugin.api;

/**
 * 插件日志器。
 *
 * <p>宿主自动添加插件标识前缀；插件不得输出密钥、令牌等敏感信息。</p>
 */
public interface PluginLogger {

    /**
     * 调试日志。
     *
     * @param message 模板
     * @param args 参数
     */
    void debug(String message, Object... args);

    /**
     * 信息日志。
     *
     * @param message 模板
     * @param args 参数
     */
    void info(String message, Object... args);

    /**
     * 警告日志。
     *
     * @param message 模板
     * @param args 参数
     */
    void warn(String message, Object... args);

    /**
     * 错误日志。
     *
     * @param message 模板
     * @param error 异常
     * @param args 参数
     */
    void error(String message, Throwable error, Object... args);
}
