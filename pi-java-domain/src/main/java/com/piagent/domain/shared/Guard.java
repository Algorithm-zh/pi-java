package com.piagent.domain.shared;

import java.util.Objects;

/**
 * 领域参数校验工具。
 *
 * <p>只做“违反即抛领域异常”的轻量检查，不替代业务规则；所有错误码由调用方指定，
 * 避免工具类把语义固定成通用的 INVALID_ARGUMENT。
 */
public final class Guard {

    private Guard() {
    }

    /**
     * 要求条件成立。
     *
     * @param condition 条件
     * @param code 失败错误码
     * @param message 失败消息
     */
    public static void require(boolean condition, DomainErrorCode code, String message) {
        if (!condition) {
            throw new DomainException(code, message);
        }
    }

    /**
     * 要求对象非空并返回。
     *
     * @param value 对象
     * @param code 失败错误码
     * @param message 失败消息
     * @param <T> 类型
     * @return 原对象
     */
    public static <T> T notNull(T value, DomainErrorCode code, String message) {
        require(value != null, code, message);
        return value;
    }

    /**
     * 要求字符串非空白并返回去空白后的值。
     *
     * @param value 字符串
     * @param code 失败错误码
     * @param message 失败消息
     * @return 去首尾空白后的字符串
     */
    public static String notBlank(String value, DomainErrorCode code, String message) {
        require(value != null && !value.isBlank(), code, message);
        return value.trim();
    }

    /**
     * 要求字符串长度不超过上限。
     *
     * @param value 字符串
     * @param maxLength 最大长度
     * @param code 失败错误码
     * @param message 失败消息
     * @return 原字符串
     */
    public static String maxLength(String value, int maxLength, DomainErrorCode code, String message) {
        Objects.requireNonNull(value, "value 不能为空");
        require(value.length() <= maxLength, code, message);
        return value;
    }
}
