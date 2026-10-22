package com.piagent.application.testsupport;

import com.piagent.domain.extension.model.PluginId;
import com.piagent.domain.extension.model.PluginInstallation;
import com.piagent.domain.extension.port.PluginRepository;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 内存插件仓储，供应用层单元测试使用。
 */
public final class InMemoryPluginRepository implements PluginRepository {

    private final Map<String, PluginInstallation> store = new LinkedHashMap<>();

    @Override
    public void insert(PluginInstallation installation) {
        String key = installation.id().value();
        if (store.putIfAbsent(key, installation) != null) {
            throw new DomainException(DomainErrorCode.PLUGIN_ALREADY_EXISTS,
                    "插件已安装: " + installation.id());
        }
    }

    @Override
    public void update(PluginInstallation installation) {
        String key = installation.id().value();
        if (!store.containsKey(key)) {
            throw new DomainException(DomainErrorCode.PLUGIN_NOT_FOUND,
                    "插件未安装: " + installation.id());
        }
        store.put(key, installation);
    }

    @Override
    public Optional<PluginInstallation> findById(PluginId id) {
        return Optional.ofNullable(store.get(id.value()));
    }

    @Override
    public List<PluginInstallation> findAll() {
        return List.copyOf(store.values());
    }
}
