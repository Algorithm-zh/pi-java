package com.piagent.domain.shared;

/**
 * 稳定的领域错误码。
 *
 * <p>错误码用于 API、日志与测试断言；新增错误码只能追加，不能改变已有含义。
 * 应用层负责把错误码映射为 HTTP 状态与用户可见消息。
 */
public enum DomainErrorCode {

    /** 参数不合法。 */
    INVALID_ARGUMENT,

    /** 会话不存在。 */
    SESSION_NOT_FOUND,

    /** 会话已归档，不能继续写入。 */
    SESSION_ARCHIVED,

    /** 会话状态不允许当前操作。 */
    INVALID_STATE_TRANSITION,

    /** entry 不存在。 */
    ENTRY_NOT_FOUND,

    /** entry 数据不合法。 */
    INVALID_ENTRY,

    /** entry 类型与 payload 不匹配。 */
    ENTRY_TYPE_MISMATCH,

    /** 分支目标不合法。 */
    INVALID_BRANCH,

    /** 分支目标不能是工具结果。 */
    ENTRY_NOT_BRANCHABLE,

    /** 并发更新冲突。 */
    CONCURRENT_MODIFICATION,

    /** 工具结果找不到对应的工具调用。 */
    TOOL_CALL_MISMATCH,

    /** payload 版本或类型暂不支持。 */
    UNSUPPORTED_PAYLOAD,

    /** 模型档案不存在。 */
    MODEL_NOT_FOUND,

    /** 模型档案已停用。 */
    MODEL_DISABLED,

    /** 模型能力不满足当前请求。 */
    MODEL_CAPABILITY_MISSING,

    /** 模型档案已存在。 */
    MODEL_ALREADY_EXISTS,

    /** 工具不存在或未注册。 */
    TOOL_NOT_FOUND,

    /** 工具参数不合法。 */
    TOOL_INVALID_ARGUMENT,

    /** 工具执行超时。 */
    TOOL_TIMEOUT,

    /** 工具执行失败。 */
    TOOL_EXECUTION_FAILED,

    /** 路径不在允许的读写范围内。 */
    PATH_OUTSIDE_WORKSPACE,

    /** 路径格式不合法。 */
    PATH_INVALID,

    /** 路径不存在。 */
    PATH_NOT_FOUND,

    /** 运行不存在或已被清理。 */
    RUN_NOT_FOUND,

    /** 同一会话已有活跃 run。 */
    RUN_ALREADY_ACTIVE,

    /** 全局并发运行数已达上限。 */
    AGENT_BUSY
}
