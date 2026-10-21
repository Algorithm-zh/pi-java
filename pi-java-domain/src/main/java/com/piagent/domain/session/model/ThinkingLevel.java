package com.piagent.domain.session.model;

/**
 * 模型思考级别。
 *
 * <p>是否真正生效由模型能力决定；领域层只保存用户选择，不在运行中静默降级。
 */
public enum ThinkingLevel {

    /** 关闭思考。 */
    OFF,

    /** 低强度。 */
    LOW,

    /** 中强度。 */
    MEDIUM,

    /** 高强度。 */
    HIGH
}
