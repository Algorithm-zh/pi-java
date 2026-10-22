package com.piagent.domain.extension.port;

import com.piagent.domain.extension.model.PluginId;
import com.piagent.domain.extension.model.PluginInstallation;
import java.util.List;
import java.util.Optional;

/**
 * 插件安装记录仓储。
 *
 * <p>只负责安装事实的持久化；运行中的 ClassLoader、工具注册与调用计数
 * 不落库，由 {@link PluginRuntime} 维护。</p>
 */
public interface PluginRepository {

    /**
     * 插入新的安装记录。
     *
     * @param installation 聚合
     */
    void insert(PluginInstallation installation);

    /**
     * 更新安装记录（按主键覆盖可变字段）。
     *
     * @param installation 聚合
     */
    void update(PluginInstallation installation);

    /**
     * 按标识查询。
     *
     * @param id 插件标识
     * @return 聚合
     */
    Optional<PluginInstallation> findById(PluginId id);

    /**
     * 查询全部安装记录。
     *
     * @return 按标识排序的列表
     */
    List<PluginInstallation> findAll();
}
