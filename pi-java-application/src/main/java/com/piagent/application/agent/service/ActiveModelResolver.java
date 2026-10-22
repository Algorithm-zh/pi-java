package com.piagent.application.agent.service;

import com.piagent.domain.model.ModelId;
import com.piagent.domain.model.ModelProfile;
import com.piagent.domain.model.port.ModelProfileRepository;
import com.piagent.domain.model.service.ModelSelectionPolicy;
import com.piagent.domain.session.model.ThinkingLevel;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import org.springframework.stereotype.Component;

/**
 * 运行期模型解析：加载档案并校验启用状态与能力。
 *
 * <p>每次 run 开始时解析一次，得到不可变快照；运行中修改模型配置不会影响
 * 当前 run，符合“单次 run 内模型不漂移”的约束。
 */
@Component
public class ActiveModelResolver {

    private final ModelProfileRepository repository;
    private final ModelSelectionPolicy selectionPolicy;

    /**
     * 创建解析器。
     *
     * @param repository 模型档案仓储
     * @param selectionPolicy 模型选择策略
     */
    public ActiveModelResolver(ModelProfileRepository repository, ModelSelectionPolicy selectionPolicy) {
        this.repository = repository;
        this.selectionPolicy = selectionPolicy;
    }

    /**
     * 解析并校验模型档案。
     *
     * @param modelProfileId 档案标识
     * @param thinkingLevel 思考级别
     * @param needsTools 是否需要工具能力
     * @return 启用且兼容的档案快照
     */
    public ModelProfile resolve(String modelProfileId, ThinkingLevel thinkingLevel, boolean needsTools) {
        ModelProfile profile = repository.findById(new ModelId(modelProfileId))
                .orElseThrow(() -> new DomainException(DomainErrorCode.MODEL_NOT_FOUND,
                        "模型档案不存在: " + modelProfileId));
        selectionPolicy.requireCompatible(profile, needsTools, thinkingLevel);
        return profile;
    }
}
