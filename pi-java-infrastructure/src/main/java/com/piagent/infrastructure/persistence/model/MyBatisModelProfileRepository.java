package com.piagent.infrastructure.persistence.model;

import com.piagent.domain.model.ModelId;
import com.piagent.domain.model.ModelProfile;
import com.piagent.domain.model.port.ModelProfileRepository;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 基于 MyBatis 的模型档案仓储。
 *
 * <p>更新使用 version 乐观锁；影响行数为 0 时统一按并发冲突处理。
 * 所有读取结果只含环境变量名，不含密钥。</p>
 */
@Repository
public class MyBatisModelProfileRepository implements ModelProfileRepository {

    private final ModelProfileMapper mapper;
    private final ModelProfilePoConverter converter;
    private final Clock clock;

    /**
     * 创建仓储。
     *
     * @param mapper MyBatis 映射器
     * @param converter 行转换器
     * @param clock 时钟
     */
    public MyBatisModelProfileRepository(ModelProfileMapper mapper, ModelProfilePoConverter converter,
            Clock clock) {
        this.mapper = mapper;
        this.converter = converter;
        this.clock = clock;
    }

    @Override
    public Optional<ModelProfile> findById(ModelId id) {
        return Optional.ofNullable(mapper.findById(id.value())).map(converter::toDomain);
    }

    @Override
    public List<ModelProfile> findAll() {
        return mapper.findAll().stream().map(converter::toDomain).toList();
    }

    @Override
    public List<ModelProfile> findEnabled() {
        return mapper.findEnabled().stream().map(converter::toDomain).toList();
    }

    @Override
    public void insert(ModelProfile profile) {
        mapper.insert(converter.toPo(profile, clock.instant()));
    }

    @Override
    public void update(ModelProfile profile) {
        int rows = mapper.update(converter.toPo(profile, clock.instant()));
        if (rows == 0) {
            throw new DomainException(DomainErrorCode.CONCURRENT_MODIFICATION,
                    "模型档案已被其他写入修改，请重新加载: " + profile.id());
        }
    }
}
