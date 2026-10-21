package com.piagent.domain.shared;

/**
 * 标识生成端口。
 *
 * <p>生产实现基于 {@link Ulid}；测试可以注入固定序列，避免随机 ID 让断言不稳定。
 */
@FunctionalInterface
public interface IdGenerator {

    /**
     * 生成一个新的标识。
     *
     * @return 26 位 ULID 字符串
     */
    String newId();
}
