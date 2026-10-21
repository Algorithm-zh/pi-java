package com.piagent.infrastructure.persistence.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolPolicyConfigCodecTest {

    private final ToolPolicyConfigCodec codec = new ToolPolicyConfigCodec(new ObjectMapper());

    @Test
    void roundTripsNestedConfig() {
        Map<String, Object> config = Map.of(
                "mode", "strict",
                "limits", Map.of("maxBytes", 1_024, "allowNetwork", false));

        Map<String, Object> decoded = codec.decode(codec.encode(config));

        assertThat(decoded).containsEntry("mode", "strict");
        assertThat(decoded.get("limits")).isInstanceOf(Map.class);
        Map<?, ?> limits = (Map<?, ?>) decoded.get("limits");
        assertThat(limits.get("maxBytes")).isEqualTo(1_024);
        assertThat(limits.get("allowNetwork")).isEqualTo(false);
    }

    @Test
    void encodesNullAsEmptyObject() {
        assertThat(codec.encode(null)).isEqualTo("{}");
    }

    @Test
    void decodesBlankAsEmpty() {
        assertThat(codec.decode(null)).isEmpty();
        assertThat(codec.decode("  ")).isEmpty();
    }

    @Test
    void rejectsNullValues() {
        assertThatThrownBy(() -> codec.decode("{\"key\":null}"))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.INVALID_ARGUMENT));
    }

    @Test
    void rejectsNonObjectJson() {
        assertThatThrownBy(() -> codec.decode("[1,2]"))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.INVALID_ARGUMENT));
    }

    @Test
    void rejectsMalformedJson() {
        assertThatThrownBy(() -> codec.decode("{broken"))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.INVALID_ARGUMENT));
    }
}
