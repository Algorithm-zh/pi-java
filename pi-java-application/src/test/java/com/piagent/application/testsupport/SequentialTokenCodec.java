package com.piagent.application.testsupport;

import com.piagent.application.identity.port.TokenCodec;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 应用层测试用的可预测令牌编码：哈希仍是 64 位小写十六进制，满足领域约束。
 */
public final class SequentialTokenCodec implements TokenCodec {

    private final AtomicInteger counter = new AtomicInteger();

    @Override
    public String newToken() {
        return "token-" + counter.incrementAndGet();
    }

    @Override
    public String hash(String token) {
        return String.format("%064x", token.hashCode());
    }
}
