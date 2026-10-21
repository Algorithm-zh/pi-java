package com.piagent.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.piagent.domain.model.ModelCapabilities;
import com.piagent.domain.model.ModelEndpoint;
import com.piagent.domain.model.ModelId;
import com.piagent.domain.model.ModelProfile;
import com.piagent.domain.model.ProviderType;
import com.piagent.domain.model.llm.LlmCallContext;
import com.piagent.domain.model.llm.LlmErrorCode;
import com.piagent.domain.model.llm.LlmGatewayException;
import com.piagent.domain.model.llm.LlmRequest;
import com.piagent.domain.model.llm.LlmStream;
import com.piagent.domain.session.model.ThinkingLevel;
import com.piagent.domain.session.model.message.TextBlock;
import com.piagent.domain.session.model.message.UserMessage;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RoutingLlmGatewayTest {

    @Test
    void routesByProviderType() {
        LlmModelProvider fake = mock(LlmModelProvider.class);
        LlmStream expected = mock(LlmStream.class);
        when(fake.supports(ProviderType.FAKE)).thenReturn(true);
        when(fake.stream(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(expected);
        RoutingLlmGateway gateway = new RoutingLlmGateway(List.of(fake));

        LlmStream actual = gateway.stream(request(ProviderType.FAKE), LlmCallContext.defaults());

        assertThat(actual).isSameAs(expected);
    }

    @Test
    void unsupportedProviderFailsWithStableCode() {
        RoutingLlmGateway gateway = new RoutingLlmGateway(List.of());

        assertThatThrownBy(() -> gateway.stream(request(ProviderType.ANTHROPIC), LlmCallContext.defaults()))
                .isInstanceOfSatisfying(LlmGatewayException.class, error -> {
                    assertThat(error.code()).isEqualTo(LlmErrorCode.UNSUPPORTED_PROVIDER);
                    assertThat(error.retryable()).isFalse();
                });
    }

    @Test
    void duplicateProviderImplementationIsRejectedAtStartup() {
        LlmModelProvider first = mock(LlmModelProvider.class);
        LlmModelProvider second = mock(LlmModelProvider.class);
        when(first.supports(ProviderType.FAKE)).thenReturn(true);
        when(second.supports(ProviderType.FAKE)).thenReturn(true);

        assertThatThrownBy(() -> new RoutingLlmGateway(List.of(first, second)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FAKE");
    }

    private LlmRequest request(ProviderType type) {
        ModelEndpoint endpoint = new ModelEndpoint(type,
                type == ProviderType.FAKE ? "" : "https://example.com",
                type == ProviderType.FAKE ? "" : "API_KEY", "test-model", 8_000, 1_000,
                ModelCapabilities.defaults());
        ModelProfile profile = ModelProfile.create(new ModelId("test-model"), "Test", endpoint);
        return new LlmRequest(profile, "", List.of(new UserMessage(
                List.of(new TextBlock("hello")), Instant.parse("2026-10-21T00:00:00Z"))),
                List.of(), ThinkingLevel.OFF);
    }
}
