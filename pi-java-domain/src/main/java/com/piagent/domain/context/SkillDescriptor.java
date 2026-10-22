package com.piagent.domain.context;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 已发现的 Skill 描述。
 *
 * <p>正文不进入 system prompt，只注入 name 与 description；agent 需要细节时
 * 通过 {@code read} 读取文件，或由用户使用 {@code /skill:name} 显式注入。</p>
 *
 * @param name skill 名称，用于 {@code /skill:name}
 * @param description 一行描述
 * @param path SKILL.md 的绝对路径
 * @param source 来源目录的可读标识
 */
public record SkillDescriptor(String name, String description, Path path, String source) {

    private static final int MAX_NAME_LENGTH = 64;
    private static final int MAX_DESCRIPTION_LENGTH = 512;
    private static final int MAX_SOURCE_LENGTH = 512;

    /**
     * 校验描述。
     *
     * @param name 名称
     * @param description 描述
     * @param path 文件路径
     * @param source 来源
     */
    public SkillDescriptor {
        name = Guard.notBlank(name, DomainErrorCode.INVALID_ARGUMENT, "skill name 不能为空");
        Guard.maxLength(name, MAX_NAME_LENGTH, DomainErrorCode.INVALID_ARGUMENT,
                "skill name 不能超过 " + MAX_NAME_LENGTH + " 个字符");
        description = Guard.notBlank(description, DomainErrorCode.INVALID_ARGUMENT,
                "skill description 不能为空");
        Guard.maxLength(description, MAX_DESCRIPTION_LENGTH, DomainErrorCode.INVALID_ARGUMENT,
                "skill description 不能超过 " + MAX_DESCRIPTION_LENGTH + " 个字符");
        Objects.requireNonNull(path, "skill path 不能为空");
        source = Guard.notBlank(source, DomainErrorCode.INVALID_ARGUMENT, "skill source 不能为空");
        Guard.maxLength(source, MAX_SOURCE_LENGTH, DomainErrorCode.INVALID_ARGUMENT,
                "skill source 不能超过 " + MAX_SOURCE_LENGTH + " 个字符");
    }
}
