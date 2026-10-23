package com.piagent.trigger.http.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.piagent.domain.model.ModelCapabilities;
import com.piagent.domain.model.ModelEndpoint;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 密钥状态测试：只判断存在性，不读取内容。
 */
class ApiKeyStatusTest {

    @Test
    void fakeProviderIsAlwaysReady() {
        assertThat(ApiKeyStatus.configured(ModelEndpoint.fake("fake"), name -> null)).isTrue();
    }

    @Test
    void detectsMissingAndBlankVariables() {
        ModelEndpoint endpoint = ModelEndpoint.openAiCompatible("https://api.example.com",
                "EXAMPLE_API_KEY", "model", 8_000, 1_000);

        assertThat(ApiKeyStatus.configured(endpoint, name -> null)).isFalse();
        assertThat(ApiKeyStatus.configured(endpoint, name -> "  ")).isFalse();
        assertThat(ApiKeyStatus.configured(endpoint, Map.of("EXAMPLE_API_KEY", "secret")::get))
                .isTrue();
    }

    @Test
    void nonFakeEndpointRequiresNamedVariable() {
        ModelEndpoint endpoint = new ModelEndpoint(
                com.piagent.domain.model.ProviderType.OPENAI_COMPATIBLE,
                "https://api.example.com", "CUSTOM_KEY", "model", 8_000, 1_000,
                ModelCapabilities.defaults());

        assertThat(ApiKeyStatus.configured(endpoint,
                name -> "CUSTOM_KEY".equals(name) ? "value" : null)).isTrue();
    }
}
