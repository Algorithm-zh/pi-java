package com.piagent.domain.subagent;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.util.List;
import java.util.Objects;

/**
 * 一次子代理派发请求。
 *
 * <p>参数来自模型对 {@code subagent} 工具的调用，在进入执行前统一校验：
 * 任务不能为空、工具白名单不能重复、超时必须合法。校验放在领域层，
 * 让“什么样的子代理请求算合法”只有一个定义。</p>
 *
 * @param mode 启动模式
 * @param task 子代理要完成的任务描述
 * @param persona 角色设定，可为 null
 * @param allowedTools 工具白名单；空列表表示继承父工具集
 * @param timeoutSeconds 超时；null 表示用策略默认值
 */
public record SubagentRequest(
        SubagentMode mode,
        String task,
        String persona,
        List<String> allowedTools,
        Integer timeoutSeconds) {

    /** 任务描述最大长度。 */
    public static final int MAX_TASK_LENGTH = 8_000;

    /** 角色设定最大长度。 */
    public static final int MAX_PERSONA_LENGTH = 1_000;

    /** 工具白名单最多条数。 */
    public static final int MAX_ALLOWED_TOOLS = 32;

    /**
     * 校验请求。
     *
     * @param mode 启动模式
     * @param task 任务
     * @param persona 角色设定
     * @param allowedTools 工具白名单
     * @param timeoutSeconds 超时
     */
    public SubagentRequest {
        Objects.requireNonNull(mode, "mode 不能为空");
        task = Guard.notBlank(task, DomainErrorCode.INVALID_ARGUMENT, "子代理任务不能为空");
        Guard.maxLength(task, MAX_TASK_LENGTH, DomainErrorCode.INVALID_ARGUMENT,
                "子代理任务不能超过 " + MAX_TASK_LENGTH + " 个字符");
        if (persona != null) {
            persona = persona.strip();
            if (persona.isEmpty()) {
                persona = null;
            } else {
                Guard.maxLength(persona, MAX_PERSONA_LENGTH, DomainErrorCode.INVALID_ARGUMENT,
                        "子代理角色设定不能超过 " + MAX_PERSONA_LENGTH + " 个字符");
            }
        }
        allowedTools = allowedTools == null ? List.of()
                : allowedTools.stream()
                        .filter(Objects::nonNull)
                        .map(String::strip)
                        .filter(value -> !value.isEmpty())
                        .distinct()
                        .toList();
        Guard.require(allowedTools.size() <= MAX_ALLOWED_TOOLS, DomainErrorCode.INVALID_ARGUMENT,
                "工具白名单不能超过 " + MAX_ALLOWED_TOOLS + " 个");
    }

    /**
     * 是否指定了工具白名单。
     *
     * @return true 表示需要与父工具集求交集
     */
    public boolean hasToolWhitelist() {
        return !allowedTools.isEmpty();
    }
}
