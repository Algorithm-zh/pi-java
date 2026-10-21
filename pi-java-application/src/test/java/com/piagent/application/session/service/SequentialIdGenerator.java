package com.piagent.application.session.service;

import com.piagent.domain.shared.IdGenerator;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 生成合法且可预测的 ULID，避免随机 ID 让断言不稳定。
 */
final class SequentialIdGenerator implements IdGenerator {

    private static final String PREFIX = "01TST00000";

    private final AtomicInteger counter = new AtomicInteger();

    @Override
    public String newId() {
        return PREFIX + String.format("%016d", counter.incrementAndGet());
    }
}
