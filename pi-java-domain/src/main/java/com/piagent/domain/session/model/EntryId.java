package com.piagent.domain.session.model;

import com.piagent.domain.shared.IdGenerator;
import com.piagent.domain.shared.Ulid;
import java.util.Objects;

/**
 * 会话 entry 标识。
 *
 * <p>每个 entry 的 ID 在追加时生成，之后永不改变；分支移动只引用它。
 */
public record EntryId(Ulid value) {

    /**
     * 校验 entry 标识。
     *
     * @param value ULID
     */
    public EntryId {
        Objects.requireNonNull(value, "entryId 不能为空");
    }

    /**
     * 解析字符串形式的 entry 标识。
     *
     * @param raw 26 位 ULID
     * @return entry 标识
     */
    public static EntryId parse(String raw) {
        return new EntryId(Ulid.parse(raw));
    }

    /**
     * 通过生成器创建新 entry 标识。
     *
     * @param generator 标识生成器
     * @return 新 entry 标识
     */
    public static EntryId newId(IdGenerator generator) {
        return new EntryId(Ulid.parse(generator.newId()));
    }

    @Override
    public String toString() {
        return value.value();
    }
}
