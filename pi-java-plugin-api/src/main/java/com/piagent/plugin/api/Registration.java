package com.piagent.plugin.api;

/**
 * 插件注册句柄。
 *
 * <p>{@link #close()} 注销对应注册项；插件启动失败时宿主会按逆序关闭。
 * 关闭必须可重复调用。</p>
 */
public interface Registration extends AutoCloseable {

    /**
     * 注销注册项。
     */
    @Override
    void close();
}
