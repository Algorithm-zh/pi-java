package com.piagent.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import org.junit.jupiter.api.Test;

class ModelEndpointTest {

    @Test
    void fakeEndpointAllowsBlankUrlAndKey() {
        ModelEndpoint endpoint = ModelEndpoint.fake("fake-coder");

        assertThat(endpoint.provider()).isEqualTo(ProviderType.FAKE);
        assertThat(endpoint.baseUrl()).isEmpty();
        assertThat(endpoint.apiKeyEnv()).isEmpty();
        assertThat(endpoint.requiresApiKey()).isFalse();
        assertThat(endpoint.ref().modelName()).isEqualTo("fake-coder");
    }

    @Test
    void openAiCompatibleNormalizesTrailingSlash() {
        ModelEndpoint endpoint = ModelEndpoint.openAiCompatible(
                "https://api.deepseek.com/v1/", "DEEPSEEK_API_KEY", "deepseek-chat", 64_000, 8_000);

        assertThat(endpoint.baseUrl()).isEqualTo("https://api.deepseek.com/v1");
        assertThat(endpoint.apiKeyEnv()).isEqualTo("DEEPSEEK_API_KEY");
        assertThat(endpoint.requiresApiKey()).isTrue();
    }

    @Test
    void nonFakeProviderRequiresBaseUrl() {
        assertInvalid(() -> new ModelEndpoint(ProviderType.OPENAI_COMPATIBLE, " ", "API_KEY",
                "model", 1000, 100, ModelCapabilities.defaults()));
    }

    @Test
    void nonFakeProviderRequiresApiKeyEnv() {
        assertInvalid(() -> new ModelEndpoint(ProviderType.OPENAI_COMPATIBLE, "https://example.com",
                "", "model", 1000, 100, ModelCapabilities.defaults()));
    }

    @Test
    void rejectsInvalidEnvironmentVariableName() {
        assertInvalid(() -> new ModelEndpoint(ProviderType.OPENAI_COMPATIBLE, "https://example.com",
                "api_key", "model", 1000, 100, ModelCapabilities.defaults()));
        assertInvalid(() -> new ModelEndpoint(ProviderType.OPENAI_COMPATIBLE, "https://example.com",
                "1API_KEY", "model", 1000, 100, ModelCapabilities.defaults()));
    }

    @Test
    void rejectsNonHttpSchemeAndMalformedUri() {
        assertInvalid(() -> new ModelEndpoint(ProviderType.OPENAI_COMPATIBLE, "ftp://example.com",
                "API_KEY", "model", 1000, 100, ModelCapabilities.defaults()));
        assertInvalid(() -> new ModelEndpoint(ProviderType.OPENAI_COMPATIBLE, "not a uri",
                "API_KEY", "model", 1000, 100, ModelCapabilities.defaults()));
        assertInvalid(() -> new ModelEndpoint(ProviderType.OPENAI_COMPATIBLE, "https:///v1",
                "API_KEY", "model", 1000, 100, ModelCapabilities.defaults()));
    }

    @Test
    void rejectsOutputLargerThanContextWindow() {
        assertInvalid(() -> new ModelEndpoint(ProviderType.FAKE, "", "", "model", 100, 101,
                ModelCapabilities.defaults()));
        assertInvalid(() -> new ModelEndpoint(ProviderType.FAKE, "", "", "model", 0, 0,
                ModelCapabilities.defaults()));
    }

    @Test
    void rejectsBlankModelName() {
        assertInvalid(() -> new ModelEndpoint(ProviderType.FAKE, "", "", " ", 100, 10,
                ModelCapabilities.defaults()));
    }

    private void assertInvalid(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.INVALID_ARGUMENT));
    }
}
