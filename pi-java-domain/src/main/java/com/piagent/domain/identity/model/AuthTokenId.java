package com.piagent.domain.identity.model;

import com.piagent.domain.shared.IdGenerator;
import com.piagent.domain.shared.Ulid;
import java.util.Objects;

/**
 * 访问令牌标识。
 *
 * <p>令牌明文只在下发时出现一次；持久化层只保存哈希与该标识。
 */
public record AuthTokenId(Ulid value) {

    /**
     * 校验令牌标识。
     *
     * @param value ULID
     */
    public AuthTokenId {
        Objects.requireNonNull(value, "tokenId 不能为空");
    }

    /**
     * 解析字符串形式的令牌标识。
     *
     * @param raw 26 位 ULID
     * @return 令牌标识
     */
    public static AuthTokenId parse(String raw) {
        return new AuthTokenId(Ulid.parse(raw));
    }

    /**
     * 通过生成器创建新令牌标识。
     *
     * @param generator 标识生成器
     * @return 新令牌标识
     */
    public static AuthTokenId newId(IdGenerator generator) {
        return new AuthTokenId(Ulid.parse(generator.newId()));
    }

    @Override
    public String toString() {
        return value.value();
    }
}
