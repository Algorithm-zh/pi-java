package com.piagent.domain.plan.model;

import java.util.Locale;

/**
 * 待办状态。
 *
 * <p>线上格式（工具参数、JSON 载荷、前端）使用小写下划线名称；
 * 枚举名只服务于 Java 代码，避免大小写契约漂移。</p>
 */
public enum PlanItemStatus {

    /** 尚未开始。 */
    PENDING("pending"),

    /** 正在处理。 */
    IN_PROGRESS("in_progress"),

    /** 已完成。 */
    DONE("done");

    private final String wireName;

    PlanItemStatus(String wireName) {
        this.wireName = wireName;
    }

    /**
     * 线上使用的稳定名称。
     *
     * @return 小写名称
     */
    public String wireName() {
        return wireName;
    }

    /**
     * 解析线上名称，兼容大写与连字符写法。
     *
     * @param value 线上名称
     * @return 状态
     * @throws IllegalArgumentException 名称未知时
     */
    public static PlanItemStatus fromWire(String value) {
        if (value == null) {
            throw new IllegalArgumentException("待办状态不能为空");
        }
        return valueOf(value.strip().toUpperCase(Locale.ROOT).replace('-', '_'));
    }
}
