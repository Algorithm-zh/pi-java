package com.piagent.domain.model.service;

import com.piagent.domain.model.ModelCapabilities;
import com.piagent.domain.model.ModelProfile;
import com.piagent.domain.session.model.ThinkingLevel;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.util.Objects;

/**
 * 模型选择策略。
 *
 * <p>在执行请求前做快速校验：档案必须启用、必须支持流式；需要工具或思考时，
 * 对应能力也必须声明。运行期 provider 仍可能拒绝，策略不替代错误处理。</p>
 */
public final class ModelSelectionPolicy {

    /**
     * 要求档案已启用。
     *
     * @param profile 模型档案
     */
    public void requireEnabled(ModelProfile profile) {
        Objects.requireNonNull(profile, "profile 不能为空");
        Guard.require(profile.enabled(), DomainErrorCode.MODEL_DISABLED,
                "模型档案已停用: " + profile.id());
    }

    /**
     * 要求档案满足本次请求能力。
     *
     * <p>流式是 v0.1 的硬要求；工具与思考按需校验，思考级别为 OFF 时不要求
     * {@code thinking} 能力。</p>
     *
     * @param profile 模型档案
     * @param needsTools 是否传入工具
     * @param thinkingLevel 思考级别，null 视为 OFF
     */
    public void requireCompatible(ModelProfile profile, boolean needsTools, ThinkingLevel thinkingLevel) {
        requireEnabled(profile);
        ModelCapabilities capabilities = profile.endpoint().capabilities();
        Guard.require(capabilities.streaming(), DomainErrorCode.MODEL_CAPABILITY_MISSING,
                "模型不支持流式输出: " + profile.id());
        Guard.require(!needsTools || capabilities.toolCalls(), DomainErrorCode.MODEL_CAPABILITY_MISSING,
                "模型不支持工具调用: " + profile.id());
        boolean needsThinking = thinkingLevel != null && thinkingLevel != ThinkingLevel.OFF;
        Guard.require(!needsThinking || capabilities.thinking(), DomainErrorCode.MODEL_CAPABILITY_MISSING,
                "模型不支持思考内容: " + profile.id());
    }
}
