package com.piagent.trigger.http.governance;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.util.Locale;

/**
 * HTTP 枚举参数解析。
 *
 * <p>只接受不区分大小写的枚举名；空白与未知值统一转成
 * {@code INVALID_ARGUMENT}，避免把 {@link IllegalArgumentException}
 * 泄漏为 500。错误消息会带上字段名，便于前端定位表单错误。</p>
 */
final class EnumParse {

    private EnumParse() {
    }

    /**
     * 解析枚举值。
     *
     * @param type 枚举类型
     * @param value 原始文本
     * @param field 字段名，用于错误消息
     * @param <E> 枚举类型
     * @return 枚举值
     */
    static <E extends Enum<E>> E parse(Class<E> type, String value, String field) {
        if (value == null || value.isBlank()) {
            throw new DomainException(DomainErrorCode.INVALID_ARGUMENT, field + " 不能为空");
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new DomainException(DomainErrorCode.INVALID_ARGUMENT,
                    "未知的 " + field + ": " + value);
        }
    }
}
