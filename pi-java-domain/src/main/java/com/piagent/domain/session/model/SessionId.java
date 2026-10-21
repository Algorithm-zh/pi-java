package com.piagent.domain.session.model;

import com.piagent.domain.shared.IdGenerator;
import com.piagent.domain.shared.Ulid;
import java.util.Objects;

/**
 * 会话标识。
 *
 * <p>与 {@link EntryId} 使用不同的包装类型，编译期阻止把 entry ID 误传为 session ID。
 */
public record SessionId(Ulid value) {

    /**
     * 校验会话标识。
     *
     * @param value ULID
     */
    public SessionId {
        Objects.requireNonNull(value, "sessionId 不能为空");
    }

    /**
     * 解析字符串形式的会话标识。
     *
     * @param raw 26 位 ULID
     * @return 会话标识
     */
    public static SessionId parse(String raw) {
        return new SessionId(Ulid.parse(raw));
    }

    /**
     * 通过生成器创建新会话标识。
     *
     * @param generator 标识生成器
     * @return 新会话标识
     */
    public static SessionId newId(IdGenerator generator) {
        return new SessionId(Ulid.parse(generator.newId()));
    }

    @Override
    public String toString() {
        return value.value();
    }
}
