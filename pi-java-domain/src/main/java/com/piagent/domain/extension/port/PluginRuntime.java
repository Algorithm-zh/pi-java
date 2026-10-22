package com.piagent.domain.extension.port;

import com.piagent.domain.extension.model.PluginId;
import com.piagent.domain.extension.model.PluginInstallation;

/**
 * 插件运行时端口。
 *
 * <p>实现方负责 classpath 隔离、实例化、生命周期回调与工具注册；领域层只表达
 * “加载这个安装记录 / 卸载这个标识”。加载失败必须抛出领域异常，应用层据此
 * 把安装记录置为 FAILED。</p>
 */
public interface PluginRuntime {

    /**
     * 加载并启用插件。
     *
     * @param installation 已安装的聚合快照
     */
    void load(PluginInstallation installation);

    /**
     * 停用插件，把工具从后续快照中移除。
     *
     * @param id 插件标识
     */
    void unload(PluginId id);

    /**
     * 查询插件是否已加载。
     *
     * @param id 插件标识
     * @return true 表示已加载
     */
    boolean isLoaded(PluginId id);
}
