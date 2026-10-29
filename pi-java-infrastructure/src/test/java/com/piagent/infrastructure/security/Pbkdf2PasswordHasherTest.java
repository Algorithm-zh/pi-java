package com.piagent.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * PBKDF2 密码哈希测试：编码格式、盐随机性与错误输入处理。
 */
class Pbkdf2PasswordHasherTest {

    private final Pbkdf2PasswordHasher hasher = new Pbkdf2PasswordHasher();

    @Test
    void hashesAndMatchesPassword() {
        String encoded = hasher.hash("correct-horse-battery");

        assertThat(encoded).startsWith("pbkdf2-sha256$210000$");
        assertThat(encoded.split("\\$")).hasSize(4);
        assertThat(hasher.matches("correct-horse-battery", encoded)).isTrue();
        assertThat(hasher.matches("wrong", encoded)).isFalse();
    }

    @Test
    void usesRandomSaltForEachHash() {
        String first = hasher.hash("same-password");
        String second = hasher.hash("same-password");

        assertThat(first).isNotEqualTo(second);
        assertThat(hasher.matches("same-password", first)).isTrue();
        assertThat(hasher.matches("same-password", second)).isTrue();
    }

    @Test
    void rejectsMalformedEncodings() {
        assertThat(hasher.matches("x", null)).isFalse();
        assertThat(hasher.matches(null, hasher.hash("x"))).isFalse();
        assertThat(hasher.matches("x", "plain-text")).isFalse();
        assertThat(hasher.matches("x", "bcrypt$10$salt$hash")).isFalse();
        assertThat(hasher.matches("x", "pbkdf2-sha256$0$c2FsdA==$aGFzaA==")).isFalse();
        assertThat(hasher.matches("x", "pbkdf2-sha256$99999999$c2FsdA==$aGFzaA==")).isFalse();
        assertThat(hasher.matches("x", "pbkdf2-sha256$210000$!!!$aGFzaA==")).isFalse();
    }
}
