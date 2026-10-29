package com.piagent.domain.identity.model;

import com.piagent.domain.shared.IdGenerator;
import com.piagent.domain.shared.Ulid;
import java.util.Objects;

/**
 * 本地账号标识。
 */
public record UserId(Ulid value) {

    /**
     * 校验账号标识。
     *
     * @param value ULID
     */
    public UserId {
        Objects.requireNonNull(value, "userId 不能为空");
    }

    /**
     * 解析字符串形式的账号标识。
     *
     * @param raw 26 位 ULID
     * @return 账号标识
     */
    public static UserId parse(String raw) {
        return new UserId(Ulid.parse(raw));
    }

    /**
     * 通过生成器创建新账号标识。
     *
     * @param generator 标识生成器
     * @return 新账号标识
     */
    public static UserId newId(IdGenerator generator) {
        return new UserId(Ulid.parse(generator.newId()));
    }

    @Override
    public String toString() {
        return value.value();
    }
}
