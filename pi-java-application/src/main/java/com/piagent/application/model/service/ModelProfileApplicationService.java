package com.piagent.application.model.service;

import com.piagent.application.model.command.CreateModelProfileCommand;
import com.piagent.application.model.command.UpdateModelProfileCommand;
import com.piagent.domain.model.ModelId;
import com.piagent.domain.model.ModelProfile;
import com.piagent.domain.model.port.ModelProfileRepository;
import com.piagent.domain.model.service.ModelSelectionPolicy;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 模型档案用例服务。
 *
 * <p>负责创建、更新、启停与查询；不解析环境变量、不访问网络。运行时选择
 * 由 {@link ModelSelectionPolicy} 校验，实际密钥读取只发生在基础设施适配器。</p>
 */
@Service
public class ModelProfileApplicationService {

    private final ModelProfileRepository repository;
    private final ModelSelectionPolicy selectionPolicy;

    /**
     * 创建用例服务。
     *
     * @param repository 档案仓储
     * @param selectionPolicy 模型选择策略
     */
    public ModelProfileApplicationService(ModelProfileRepository repository,
            ModelSelectionPolicy selectionPolicy) {
        this.repository = repository;
        this.selectionPolicy = selectionPolicy;
    }

    /**
     * 创建模型档案。
     *
     * @param command 创建命令
     * @return 新档案
     */
    @Transactional
    public ModelProfile create(CreateModelProfileCommand command) {
        ModelId id = new ModelId(command.modelId());
        if (repository.findById(id).isPresent()) {
            throw new DomainException(DomainErrorCode.MODEL_ALREADY_EXISTS, "模型档案已存在: " + id);
        }
        ModelProfile profile = ModelProfile.create(id, command.displayName(), command.endpoint());
        repository.insert(profile);
        return profile;
    }

    /**
     * 更新展示名与端点。
     *
     * @param command 更新命令
     * @return 更新后的档案
     */
    @Transactional
    public ModelProfile update(UpdateModelProfileCommand command) {
        ModelProfile current = require(new ModelId(command.modelId()));
        ModelProfile updated = current.rename(command.displayName())
                .updateEndpoint(command.endpoint());
        repository.update(updated);
        return updated.withVersion(current.version() + 1);
    }

    /**
     * 启用档案。
     *
     * @param modelId 档案标识
     * @return 启用后的档案
     */
    @Transactional
    public ModelProfile enable(String modelId) {
        return changeEnabled(new ModelId(modelId), true);
    }

    /**
     * 停用档案。
     *
     * @param modelId 档案标识
     * @return 停用后的档案
     */
    @Transactional
    public ModelProfile disable(String modelId) {
        return changeEnabled(new ModelId(modelId), false);
    }

    /**
     * 按标识查询。
     *
     * @param modelId 档案标识
     * @return 档案
     */
    @Transactional(readOnly = true)
    public ModelProfile find(String modelId) {
        return require(new ModelId(modelId));
    }

    /**
     * 查询全部档案。
     *
     * @return 档案列表
     */
    @Transactional(readOnly = true)
    public List<ModelProfile> listAll() {
        return repository.findAll();
    }

    /**
     * 查询启用档案。
     *
     * @return 启用档案列表
     */
    @Transactional(readOnly = true)
    public List<ModelProfile> listEnabled() {
        return repository.findEnabled();
    }

    /**
     * 加载档案并校验已启用，供运行期使用。
     *
     * @param modelId 档案标识
     * @return 启用档案
     */
    @Transactional(readOnly = true)
    public ModelProfile requireEnabled(String modelId) {
        ModelProfile profile = require(new ModelId(modelId));
        selectionPolicy.requireEnabled(profile);
        return profile;
    }

    /**
     * 读取可选档案。
     *
     * @param modelId 档案标识
     * @return 档案，不存在时为空
     */
    @Transactional(readOnly = true)
    public Optional<ModelProfile> findOptional(String modelId) {
        return repository.findById(new ModelId(modelId));
    }

    private ModelProfile changeEnabled(ModelId id, boolean enabled) {
        ModelProfile current = require(id);
        if (current.enabled() == enabled) {
            return current;
        }
        ModelProfile updated = enabled ? current.enable() : current.disable();
        repository.update(updated);
        return updated.withVersion(current.version() + 1);
    }

    private ModelProfile require(ModelId id) {
        return repository.findById(id)
                .orElseThrow(() -> new DomainException(DomainErrorCode.MODEL_NOT_FOUND,
                        "模型档案不存在: " + id));
    }
}
