package com.piagent.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * SHA-256 令牌编码测试：URL 安全明文与稳定哈希。
 */
class Sha256TokenCodecTest {

    private final Sha256TokenCodec codec = new Sha256TokenCodec();

    @Test
    void generatesUrlSafeUnguessableTokens() {
        String first = codec.newToken();
        String second = codec.newToken();

        assertThat(first).isNotEqualTo(second);
        assertThat(first).matches("[A-Za-z0-9_-]{43}");
    }

    @Test
    void hashesToStableLowercaseHex() {
        String hash = codec.hash("token-1");

        assertThat(hash).matches("[0-9a-f]{64}");
        assertThat(codec.hash("token-1")).isEqualTo(hash);
        assertThat(codec.hash("token-2")).isNotEqualTo(hash);
    }
}
