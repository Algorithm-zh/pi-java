package com.piagent.plugin.api;

/**
 * 插件入口。
 *
 * <p>宿主通过无参构造器实例化入口类并调用 {@link #start(PluginContext)}。
 * 入口类与所有依赖必须能在插件 ClassLoader 中加载，不能引用 Spring、MyBatis
 * 或宿主内部类型。</p>
 */
public interface PiPlugin {

    /**
     * 插件描述。
     *
     * @return 描述
     */
    PluginDescriptor descriptor();

    /**
     * 启动插件并完成注册。
     *
     * <p>抛异常时宿主回滚本次全部注册并关闭 ClassLoader。</p>
     *
     * @param context 宿主上下文
     * @throws Exception 启动失败
     */
    void start(PluginContext context) throws Exception;

    /**
     * 停止插件。
     *
     * <p>默认空实现；宿主在进程退出或显式卸载时调用。</p>
     */
    default void stop() {
    }
}
