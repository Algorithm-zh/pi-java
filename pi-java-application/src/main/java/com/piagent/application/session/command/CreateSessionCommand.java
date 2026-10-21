package com.piagent.application.session.command;

import com.piagent.domain.session.model.ThinkingLevel;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 创建用户会话命令。
 *
 * @param title 会话标题
 * @param cwd 工作目录，必须是绝对路径
 * @param modelProfileId 模型档案标识
 * @param thinkingLevel 思考级别；为 null 时使用 MEDIUM
 */
public record CreateSessionCommand(String title, Path cwd, String modelProfileId, ThinkingLevel thinkingLevel) {

    /**
     * 校验命令并补默认思考级别。
     *
     * @param title 标题
     * @param cwd 工作目录
     * @param modelProfileId 模型档案
     * @param thinkingLevel 思考级别
     */
    public CreateSessionCommand {
        title = Guard.notBlank(title, DomainErrorCode.INVALID_ARGUMENT, "会话标题不能为空");
        Objects.requireNonNull(cwd, "cwd 不能为空");
        Guard.require(cwd.isAbsolute(), DomainErrorCode.INVALID_ARGUMENT, "cwd 必须是绝对路径");
        cwd = cwd.normalize();
        modelProfileId = Guard.notBlank(modelProfileId, DomainErrorCode.INVALID_ARGUMENT, "modelProfileId 不能为空");
        thinkingLevel = thinkingLevel == null ? ThinkingLevel.MEDIUM : thinkingLevel;
    }
}
