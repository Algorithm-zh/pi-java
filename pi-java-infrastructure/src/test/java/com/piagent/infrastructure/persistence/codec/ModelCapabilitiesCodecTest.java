package com.piagent.infrastructure.persistence.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.piagent.domain.model.ModelCapabilities;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import org.junit.jupiter.api.Test;

class ModelCapabilitiesCodecTest {

    private final ModelCapabilitiesCodec codec = new ModelCapabilitiesCodec(new ObjectMapper());

    @Test
    void roundTripsCapabilities() {
        ModelCapabilities capabilities = new ModelCapabilities(true, false, true);

        String json = codec.encode(capabilities);

        assertThat(json).contains("\"streaming\":true", "\"toolCalls\":false", "\"thinking\":true");
        assertThat(codec.decode(json)).isEqualTo(capabilities);
    }

    @Test
    void missingFieldsDefaultToFalse() {
        assertThat(codec.decode("{\"streaming\":true}"))
                .isEqualTo(new ModelCapabilities(true, false, false));
        assertThat(codec.decode("{}"))
                .isEqualTo(new ModelCapabilities(false, false, false));
    }

    @Test
    void ignoresUnknownFields() {
        assertThat(codec.decode("{\"streaming\":true,\"future\":\"ignored\"}").streaming()).isTrue();
    }

    @Test
    void rejectsMalformedOrNonObjectJson() {
        assertInvalid("not-json");
        assertInvalid("[]");
        assertInvalid("null");
    }

    private void assertInvalid(String json) {
        assertThatThrownBy(() -> codec.decode(json))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.INVALID_ARGUMENT));
    }
}
