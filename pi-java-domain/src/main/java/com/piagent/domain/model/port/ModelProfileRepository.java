package com.piagent.domain.model.port;

import com.piagent.domain.model.ModelId;
import com.piagent.domain.model.ModelProfile;
import java.util.List;
import java.util.Optional;

/**
 * 模型档案仓储。
 *
 * <p>更新使用 {@code profile.version()} 做乐观锁；影响行数为 0 时必须抛出
 * {@code CONCURRENT_MODIFICATION}。按标识或列表读取的结果不得包含密钥明文。</p>
 */
public interface ModelProfileRepository {

    /**
     * 按标识查询。
     *
     * @param id 档案标识
     * @return 档案，不存在时为空
     */
    Optional<ModelProfile> findById(ModelId id);

    /**
     * 查询全部档案。
     *
     * @return 档案列表，按标识排序
     */
    List<ModelProfile> findAll();

    /**
     * 查询启用档案。
     *
     * @return 启用档案列表
     */
    List<ModelProfile> findEnabled();

    /**
     * 插入新档案。
     *
     * @param profile 档案
     */
    void insert(ModelProfile profile);

    /**
     * 以乐观锁更新档案。
     *
     * @param profile 档案；其 version 作为 CAS 期望值
     */
    void update(ModelProfile profile);
}
