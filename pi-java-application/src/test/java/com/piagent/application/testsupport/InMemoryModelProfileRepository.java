package com.piagent.application.testsupport;

import com.piagent.domain.model.ModelId;
import com.piagent.domain.model.ModelProfile;
import com.piagent.domain.model.port.ModelProfileRepository;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 内存模型档案仓储，供应用层单元测试使用。
 *
 * <p>与真实仓储一致：update 使用旧 version 做 CAS，成功后版本加一。</p>
 */
public final class InMemoryModelProfileRepository implements ModelProfileRepository {

    private final Map<String, ModelProfile> store = new LinkedHashMap<>();

    @Override
    public Optional<ModelProfile> findById(ModelId id) {
        return Optional.ofNullable(store.get(id.value()));
    }

    @Override
    public List<ModelProfile> findAll() {
        return List.copyOf(store.values());
    }

    @Override
    public List<ModelProfile> findEnabled() {
        return store.values().stream().filter(ModelProfile::enabled).toList();
    }

    @Override
    public void insert(ModelProfile profile) {
        if (store.putIfAbsent(profile.id().value(), profile) != null) {
            throw new DomainException(DomainErrorCode.MODEL_ALREADY_EXISTS,
                    "模型档案已存在: " + profile.id());
        }
    }

    @Override
    public void update(ModelProfile profile) {
        ModelProfile current = store.get(profile.id().value());
        if (current == null || current.version() != profile.version()) {
            throw new DomainException(DomainErrorCode.CONCURRENT_MODIFICATION,
                    "模型档案已被其他写入修改: " + profile.id());
        }
        store.put(profile.id().value(), profile.withVersion(current.version() + 1));
    }
}
