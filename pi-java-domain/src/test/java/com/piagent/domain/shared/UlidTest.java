package com.piagent.domain.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class UlidTest {

    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void generatesTimeOrderedUlid() {
        Ulid earlier = Ulid.generate(BASE);
        Ulid later = Ulid.generate(BASE.plusMillis(1000));

        assertThat(earlier.value()).hasSize(26);
        assertThat(earlier).isLessThan(later);
        assertThat(earlier.instant()).isEqualTo(BASE);
        assertThat(later.instant()).isEqualTo(BASE.plusMillis(1000));
    }

    @Test
    void normalizesLowercase() {
        Ulid ulid = Ulid.parse("01java00000000000000000000");

        assertThat(ulid.value()).startsWith("01JAVA");
        assertThat(Ulid.parse(ulid.value())).isEqualTo(ulid);
    }

    @Test
    void rejectsInvalidInput() {
        assertThatThrownBy(() -> Ulid.parse("SHORT")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Ulid.parse("01JAVA0000IIIIIIIIIIIIIIII"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非法字符");
        assertThatThrownBy(() -> Ulid.parse("Z1JAVA00000000000000000000"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("时间戳溢出");
    }
}
