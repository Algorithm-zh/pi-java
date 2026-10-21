package com.piagent.infrastructure.llm.openai;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.piagent.domain.model.ModelEndpoint;
import com.piagent.domain.model.ModelId;
import com.piagent.domain.model.ModelProfile;
import com.piagent.domain.model.llm.DoneDelta;
import com.piagent.domain.model.llm.LlmCallContext;
import com.piagent.domain.model.llm.LlmDelta;
import com.piagent.domain.model.llm.LlmErrorCode;
import com.piagent.domain.model.llm.LlmGatewayException;
import com.piagent.domain.model.llm.LlmRequest;
import com.piagent.domain.model.llm.LlmResponse;
import com.piagent.domain.model.llm.LlmStream;
import com.piagent.domain.model.llm.TextDelta;
import com.piagent.domain.model.llm.ToolCallDelta;
import com.piagent.domain.session.model.ThinkingLevel;
import com.piagent.domain.session.model.message.AssistantStopReason;
import com.piagent.domain.session.model.message.TextBlock;
import com.piagent.domain.session.model.message.ToolCallBlock;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * OpenAI-compatible provider 的端到端流式测试。
 *
 * <p>使用 WireMock 覆盖 SSE 文本、工具调用、重试、错误映射与取消；
 * 全部测试离线运行，不访问真实 provider。</p>
 */
class OpenAiCompatibleProviderTest {

    private static final String PATH = "/v1/chat/completions";
    private static final String API_KEY_VAR = "PI_TEST_API_KEY";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @RegisterExtension
    static final WireMockExtension WIREMOCK = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @BeforeEach
    void resetServer() {
        WIREMOCK.resetAll();
    }

    @Test
    void streamsTextUsageAndStopReason() {
        WIREMOCK.stubFor(post(urlEqualTo(PATH)).willReturn(ok(sse(
                chunk("{\"content\":\"你好\"}"),
                chunk("{\"content\":\"，世界\"}"),
                "{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}",
                "{\"choices\":[],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":7,"
                        + "\"total_tokens\":107,\"prompt_tokens_details\":{\"cached_tokens\":20}}}",
                "[DONE]"))));

        LlmStream stream = provider().stream(request(API_KEY_VAR), context());
        List<LlmDelta> deltas = drain(stream);

        assertThat(deltas).filteredOn(TextDelta.class::isInstance)
                .extracting(delta -> ((TextDelta) delta).text())
                .containsExactly("你好", "，世界");
        assertThat(lastDelta(deltas)).isInstanceOfSatisfying(DoneDelta.class,
                done -> assertThat(done.stopReason()).isEqualTo(AssistantStopReason.STOP));

        LlmResponse response = stream.response().orElseThrow();
        assertThat(response.content()).containsExactly(new TextBlock("你好，世界"));
        assertThat(response.usage().input()).isEqualTo(100);
        assertThat(response.usage().output()).isEqualTo(7);
        assertThat(response.usage().cacheRead()).isEqualTo(20);
        assertThat(response.usage().totalTokens()).isEqualTo(107);
        assertThat(response.stopReason()).isEqualTo(AssistantStopReason.STOP);
        assertThat(response.errorMessage()).isNull();

        WIREMOCK.verify(1, postRequestedFor(urlEqualTo(PATH))
                .withHeader("Authorization", equalTo("Bearer test-secret"))
                .withRequestBody(containing("\"stream\":true"))
                .withRequestBody(containing("\"model\":\"test-model\"")));
    }

    @Test
    void mergesFragmentedToolCallsByIndex() {
        WIREMOCK.stubFor(post(urlEqualTo(PATH)).willReturn(ok(sse(
                "{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,"
                        + "\"id\":\"call_1\",\"function\":{\"name\":\"read\","
                        + "\"arguments\":\"{\\\"path\\\":\"}}]}}]}",
                "{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,"
                        + "\"function\":{\"arguments\":\"\\\"a.txt\\\"}\"}}]}}]}",
                "{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}",
                "[DONE]"))));

        LlmStream stream = provider().stream(request(API_KEY_VAR), context());
        List<LlmDelta> deltas = drain(stream);

        assertThat(deltas).filteredOn(ToolCallDelta.class::isInstance)
                .extracting(delta -> {
                    ToolCallDelta call = (ToolCallDelta) delta;
                    return tuple(call.index(), call.toolCallId(), call.toolName(),
                            call.argumentsFragment());
                })
                .containsExactly(
                        tuple(0, "call_1", "read", "{\"path\":"),
                        tuple(0, "", "", "\"a.txt\"}"));
        assertThat(lastDelta(deltas)).isInstanceOfSatisfying(DoneDelta.class,
                done -> assertThat(done.stopReason()).isEqualTo(AssistantStopReason.TOOL_USE));

        LlmResponse response = stream.response().orElseThrow();
        assertThat(response.content()).containsExactly(
                new ToolCallBlock("call_1", "read", Map.of("path", "a.txt")));
        assertThat(response.stopReason()).isEqualTo(AssistantStopReason.TOOL_USE);
    }

    @Test
    void retriesRateLimitBeforeFirstDelta() {
        WIREMOCK.stubFor(post(urlEqualTo(PATH)).inScenario("retry")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(429)
                        .withBody("{\"error\":{\"message\":\"slow down\"}}"))
                .willSetStateTo("ready"));
        WIREMOCK.stubFor(post(urlEqualTo(PATH)).inScenario("retry")
                .whenScenarioStateIs("ready")
                .willReturn(ok(sse(chunk("{\"content\":\"ok\"}"), "[DONE]"))));

        List<LlmDelta> deltas = drain(provider().stream(request(API_KEY_VAR), context()));

        assertThat(deltas).contains(new TextDelta("ok"));
        WIREMOCK.verify(2, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void failsAfterThreeServerErrors() {
        WIREMOCK.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(500)
                .withBody("{\"error\":{\"message\":\"boom\"}}")));

        assertThatThrownBy(() -> drain(provider().stream(request(API_KEY_VAR), context())))
                .isInstanceOfSatisfying(LlmGatewayException.class, error -> {
                    assertThat(error.code()).isEqualTo(LlmErrorCode.SERVER_ERROR);
                    assertThat(error.retryable()).isTrue();
                });
        WIREMOCK.verify(3, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void doesNotRetryAuthenticationFailure() {
        WIREMOCK.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(401)
                .withBody("{\"error\":{\"message\":\"bad key\"}}")));

        assertThatThrownBy(() -> drain(provider().stream(request(API_KEY_VAR), context())))
                .isInstanceOfSatisfying(LlmGatewayException.class, error -> {
                    assertThat(error.code()).isEqualTo(LlmErrorCode.AUTHENTICATION);
                    assertThat(error.retryable()).isFalse();
                });
        WIREMOCK.verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void mapsContextOverflowWithoutRetry() {
        WIREMOCK.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(400)
                .withBody("{\"error\":{\"message\":\"maximum context length exceeded, "
                        + "context_length_exceeded\",\"type\":\"invalid_request_error\"}}")));

        assertThatThrownBy(() -> drain(provider().stream(request(API_KEY_VAR), context())))
                .isInstanceOfSatisfying(LlmGatewayException.class,
                        error -> assertThat(error.code()).isEqualTo(LlmErrorCode.CONTEXT_OVERFLOW));
        WIREMOCK.verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void malformedSseFailsWithoutRetry() {
        WIREMOCK.stubFor(post(urlEqualTo(PATH)).willReturn(ok(sse("not-json", "[DONE]"))));

        assertThatThrownBy(() -> drain(provider().stream(request(API_KEY_VAR), context())))
                .isInstanceOfSatisfying(LlmGatewayException.class,
                        error -> assertThat(error.code()).isEqualTo(LlmErrorCode.MALFORMED_RESPONSE));
        WIREMOCK.verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void midStreamFailureDoesNotRetryAfterDelta() {
        WIREMOCK.stubFor(post(urlEqualTo(PATH)).willReturn(ok(sse(
                chunk("{\"content\":\"hi\"}"), "{oops", "[DONE]"))));

        LlmStream stream = provider().stream(request(API_KEY_VAR), context());
        assertThat(stream.hasNext()).isTrue();
        assertThat(stream.next()).isEqualTo(new TextDelta("hi"));

        assertThatThrownBy(stream::hasNext)
                .isInstanceOfSatisfying(LlmGatewayException.class, error ->
                        assertThat(error.code()).isEqualTo(LlmErrorCode.MALFORMED_RESPONSE));
        assertThat(stream.response()).isEmpty();
        WIREMOCK.verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void missingApiKeyFailsBeforeNetwork() {
        assertThatThrownBy(() -> provider().stream(request("PI_TEST_UNSET"), context()))
                .isInstanceOfSatisfying(LlmGatewayException.class,
                        error -> assertThat(error.code()).isEqualTo(LlmErrorCode.MISSING_API_KEY));
        WIREMOCK.verify(0, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void cancelBeforeFirstReadSkipsNetwork() {
        LlmStream stream = provider().stream(request(API_KEY_VAR), context());
        stream.cancel();

        assertThat(stream.hasNext()).isFalse();
        assertThat(stream.isCancelled()).isTrue();
        assertThat(stream.response()).isEmpty();
        WIREMOCK.verify(0, postRequestedFor(urlEqualTo(PATH)));
    }

    private static OpenAiCompatibleProvider provider() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        return new OpenAiCompatibleProvider(MAPPER,
                name -> API_KEY_VAR.equals(name) ? "test-secret" : null, client);
    }

    private static LlmRequest request(String apiKeyEnv) {
        ModelProfile profile = ModelProfile.create(new ModelId("test-model"), "测试模型",
                ModelEndpoint.openAiCompatible(WIREMOCK.baseUrl() + "/v1", apiKeyEnv,
                        "test-model", 128_000, 8_192));
        return new LlmRequest(profile, "你是测试助手", List.of(), List.of(), ThinkingLevel.OFF);
    }

    private static LlmCallContext context() {
        return new LlmCallContext(Duration.ofSeconds(10));
    }

    private static List<LlmDelta> drain(LlmStream stream) {
        List<LlmDelta> deltas = new ArrayList<>();
        while (stream.hasNext()) {
            deltas.add(stream.next());
        }
        return deltas;
    }

    private static LlmDelta lastDelta(List<LlmDelta> deltas) {
        assertThat(deltas).isNotEmpty();
        return deltas.get(deltas.size() - 1);
    }

    private static String chunk(String deltaContent) {
        return "{\"choices\":[{\"index\":0,\"delta\":" + deltaContent + "}]}";
    }

    private static String sse(String... data) {
        StringBuilder body = new StringBuilder();
        for (String item : data) {
            body.append("data: ").append(item).append("\n\n");
        }
        return body.toString();
    }

    private static ResponseDefinitionBuilder ok(String body) {
        return aResponse().withStatus(200)
                .withHeader("Content-Type", "text/event-stream")
                .withBody(body);
    }
}
