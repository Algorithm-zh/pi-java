package com.piagent.application.testsupport;

import com.piagent.domain.extension.model.PluginId;
import com.piagent.domain.extension.model.PluginInstallation;
import com.piagent.domain.extension.port.PluginRuntime;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 记录加载行为的插件运行时替身。
 */
public final class RecordingPluginRuntime implements PluginRuntime {

    private final Map<PluginId, PluginInstallation> loaded = new LinkedHashMap<>();
    private final Set<PluginId> failing = new LinkedHashSet<>();
    private int loadCount;
    private int unloadCount;

    @Override
    public void load(PluginInstallation installation) {
        loadCount++;
        PluginId id = installation.id();
        if (failing.contains(id)) {
            throw new DomainException(DomainErrorCode.PLUGIN_LOAD_FAILED,
                    "模拟加载失败: " + id);
        }
        if (loaded.putIfAbsent(id, installation) != null) {
            throw new DomainException(DomainErrorCode.PLUGIN_INVALID_STATE,
                    "插件已加载: " + id);
        }
    }

    @Override
    public void unload(PluginId id) {
        unloadCount++;
        if (loaded.remove(id) == null) {
            throw new DomainException(DomainErrorCode.PLUGIN_INVALID_STATE,
                    "插件未加载: " + id);
        }
    }

    @Override
    public boolean isLoaded(PluginId id) {
        return loaded.containsKey(id);
    }

    /**
     * 让指定插件在加载时失败。
     *
     * @param id 插件标识
     */
    public void failOn(PluginId id) {
        failing.add(id);
    }

    /**
     * 允许指定插件重新加载。
     *
     * @param id 插件标识
     */
    public void recover(PluginId id) {
        failing.remove(id);
    }

    /**
     * 加载次数。
     *
     * @return 次数
     */
    public int loadCount() {
        return loadCount;
    }

    /**
     * 卸载次数。
     *
     * @return 次数
     */
    public int unloadCount() {
        return unloadCount;
    }
}
