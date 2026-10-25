package com.piagent.domain.session.model;

/**
 * 会话 entry 类型。
 *
 * <p>类型与 payload 必须一一匹配，由 {@link SessionEntry} 构造时校验。
 * 新增类型需要同步数据库注释、投影器与 payload codec。
 */
public enum EntryType {

    /** 用户、助手或工具结果消息。 */
    MESSAGE,

    /** 扩展注入、参与上下文的消息。 */
    CUSTOM_MESSAGE,

    /** 压缩摘要。 */
    COMPACTION,

    /** 分支切换摘要。 */
    BRANCH_SUMMARY,

    /** 模型或思考级别切换。 */
    MODEL_CHANGE,

    /** 扩展状态，不进入上下文。 */
    CUSTOM,

    /** 书签。 */
    LABEL,

    /** 会话展示信息。 */
    SESSION_INFO,

    /** 审批决策审计。 */
    APPROVAL,

    /** 一次 run 的结束摘要。 */
    RUN_SUMMARY,

    /** run 开始前的工作区检查点。 */
    GIT_CHECKPOINT,

    /** 一轮工具执行后的工作区改动。 */
    GIT_CHANGES
}
