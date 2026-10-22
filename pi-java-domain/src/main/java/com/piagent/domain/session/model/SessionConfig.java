package com.piagent.domain.session.model;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 会话执行配置：工作目录、模型、思考级别与沙箱快照。
 *
 * <p>沙箱跟随会话而不是运行中的全局策略，保证历史会话重放时权限语义不变。
 *
 * @param cwd 工作目录，必须是绝对路径
 * @param modelProfileId 模型档案标识
 * @param thinkingLevel 思考级别
 * @param sandbox 创建会话时固定的沙箱快照
 */
public record SessionConfig(Path cwd, String modelProfileId, ThinkingLevel thinkingLevel, SandboxSnapshot sandbox) {

    /**
     * 校验执行配置。
     *
     * @param cwd 工作目录
     * @param modelProfileId 模型档案
     * @param thinkingLevel 思考级别
     * @param sandbox 沙箱快照
     */
    public SessionConfig {
        Objects.requireNonNull(cwd, "cwd 不能为空");
        Guard.require(cwd.isAbsolute(), DomainErrorCode.INVALID_ARGUMENT, "cwd 必须是绝对路径: " + cwd);
        cwd = cwd.normalize();
        modelProfileId = Guard.notBlank(modelProfileId, DomainErrorCode.INVALID_ARGUMENT, "modelProfileId 不能为空");
        Guard.maxLength(modelProfileId, 64, DomainErrorCode.INVALID_ARGUMENT, "modelProfileId 不能超过 64 个字符");
        Objects.requireNonNull(thinkingLevel, "thinkingLevel 不能为空");
        Objects.requireNonNull(sandbox, "sandbox 不能为空");
    }

    /**
     * 以工作目录为唯一可写根的默认配置。
     *
     * <p>v0.1 默认关闭思考：provider 协议尚未翻译思考参数，
     * 且多数聊天模型不声明 thinking 能力；需要时在创建命令中显式指定。</p>
     *
     * @param cwd 工作目录
     * @param modelProfileId 模型档案
     * @return 默认配置
     */
    public static SessionConfig forWorkspace(Path cwd, String modelProfileId) {
        return new SessionConfig(cwd, modelProfileId, ThinkingLevel.OFF, SandboxSnapshot.forWorkspace(cwd));
    }

    /**
     * 切换模型与思考级别，保留工作目录与沙箱。
     *
     * @param newModelProfileId 新模型档案
     * @param newThinkingLevel 新思考级别
     * @return 新配置
     */
    public SessionConfig withModel(String newModelProfileId, ThinkingLevel newThinkingLevel) {
        return new SessionConfig(cwd, newModelProfileId, newThinkingLevel, sandbox);
    }
}
