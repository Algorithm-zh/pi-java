package com.piagent.infrastructure.persistence.extension;

import com.piagent.domain.extension.model.PluginId;
import com.piagent.domain.extension.model.PluginInstallation;
import com.piagent.domain.extension.port.PluginRepository;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 基于 MyBatis 的插件安装记录仓储。
 *
 * <p>不做缓存：插件启停是低频操作，且运行时状态必须与数据库最近一次
 * 成功写入保持一致。更新 0 行视为记录已被删除，报 {@code PLUGIN_NOT_FOUND}。</p>
 */
@Repository
public class MyBatisPluginRepository implements PluginRepository {

    private final PluginMapper mapper;
    private final PluginPoConverter converter;

    /**
     * 创建仓储。
     *
     * @param mapper MyBatis 映射器
     * @param converter 行转换器
     */
    public MyBatisPluginRepository(PluginMapper mapper, PluginPoConverter converter) {
        this.mapper = mapper;
        this.converter = converter;
    }

    @Override
    public void insert(PluginInstallation installation) {
        mapper.insert(converter.toPo(installation));
    }

    @Override
    public void update(PluginInstallation installation) {
        int rows = mapper.update(converter.toPo(installation));
        if (rows == 0) {
            throw new DomainException(DomainErrorCode.PLUGIN_NOT_FOUND,
                    "插件安装记录不存在: " + installation.id());
        }
    }

    @Override
    public Optional<PluginInstallation> findById(PluginId id) {
        return Optional.ofNullable(mapper.findById(id.value())).map(converter::toDomain);
    }

    @Override
    public List<PluginInstallation> findAll() {
        return mapper.findAll().stream().map(converter::toDomain).toList();
    }
}
