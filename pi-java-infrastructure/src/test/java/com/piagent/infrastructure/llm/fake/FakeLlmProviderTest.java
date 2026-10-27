package com.piagent.infrastructure.llm.fake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.domain.model.ModelCapabilities;
import com.piagent.domain.model.ModelEndpoint;
import com.piagent.domain.model.ModelId;
import com.piagent.domain.model.ModelProfile;
import com.piagent.domain.model.ProviderType;
import com.piagent.domain.model.Usage;
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
import com.piagent.domain.session.model.message.ImageBlock;
import com.piagent.domain.session.model.message.TextBlock;
import com.piagent.domain.session.model.message.ToolCallBlock;
import com.piagent.domain.session.model.message.UserMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;

class FakeLlmProviderTest {

    @Test
    void streamsTextAndFinalResponse() {
        FakeLlmProvider provider = new FakeLlmProvider(FakeTurn.text("你好").withUsage(
                new Usage(3, 2, 0, 0, 5)));
        LlmStream stream = provider.stream(request(), LlmCallContext.defaults());

        List<LlmDelta> deltas = drain(stream);

        assertThat(deltas).hasSize(2);
        assertThat(deltas.get(0)).isEqualTo(new TextDelta("你好"));
        assertThat(deltas.get(1)).isInstanceOf(DoneDelta.class);
        assertThat(stream.response()).hasValueSatisfying(response -> {
            assertThat(response.content()).containsExactly(new TextBlock("你好"));
            assertThat(response.usage().totalTokens()).isEqualTo(5);
            assertThat(response.stopReason()).isEqualTo(AssistantStopReason.STOP);
        });
    }

    @Test
    void streamsFragmentedToolCallAndAssemblesArguments() {
        FakeLlmProvider provider = new FakeLlmProvider(
                FakeTurn.toolCall("call_1", "read", Map.of("path", "a.txt")));
        LlmStream stream = provider.stream(request(), LlmCallContext.defaults());

        List<LlmDelta> deltas = drain(stream);

        assertThat(deltas).filteredOn(ToolCallDelta.class::isInstance).hasSize(2);
        assertThat(deltas.get(0)).isInstanceOfSatisfying(ToolCallDelta.class,
                delta -> assertThat(delta.toolCallId()).isEqualTo("call_1"));
        assertThat(stream.response().orElseThrow().content())
                .containsExactly(new ToolCallBlock("call_1", "read", Map.of("path", "a.txt")));
        assertThat(stream.response().orElseThrow().stopReason()).isEqualTo(AssistantStopReason.TOOL_USE);
    }

    @Test
    void throwsAfterPartialDeltas() {
        FakeLlmProvider provider = new FakeLlmProvider(FakeTurn.partialThenFailure("部分",
                LlmErrorCode.SERVER_ERROR, "boom"));
        LlmStream stream = provider.stream(request(), LlmCallContext.defaults());

        assertThat(stream.next()).isEqualTo(new TextDelta("部分"));
        assertThatThrownBy(stream::next)
                .isInstanceOfSatisfying(LlmGatewayException.class, error -> {
                    assertThat(error.code()).isEqualTo(LlmErrorCode.SERVER_ERROR);
                    assertThat(error.retryable()).isTrue();
                });
        assertThat(stream.response()).isEmpty();
    }

    @Test
    void failsBeforeAnyDelta() {
        FakeLlmProvider provider = new FakeLlmProvider(
                FakeTurn.failure(LlmErrorCode.AUTHENTICATION, "bad key"));
        LlmStream stream = provider.stream(request(), LlmCallContext.defaults());

        assertThatThrownBy(stream::next)
                .isInstanceOfSatisfying(LlmGatewayException.class,
                        error -> assertThat(error.code()).isEqualTo(LlmErrorCode.AUTHENTICATION));
    }

    @Test
    void cancelInterruptsSlowStream() throws Exception {
        FakeTurn slow = FakeTurn.text("慢").withDelay(Duration.ofSeconds(5));
        LlmStream stream = new FakeLlmProvider(slow).stream(request(), LlmCallContext.defaults());
        Thread worker = Thread.ofVirtual().start(() -> {
            try {
                stream.next();
            } catch (NoSuchElementException ignored) {
                // 取消后允许流直接结束。
            }
        });

        Thread.sleep(80);
        stream.cancel();
        worker.join(1_000);

        assertThat(worker.isAlive()).isFalse();
        assertThat(stream.isCancelled()).isTrue();
        assertThat(stream.hasNext()).isFalse();
        assertThat(stream.response()).isEmpty();
    }

    @Test
    void closeStopsIteration() {
        LlmStream stream = new FakeLlmProvider(FakeTurn.text("x"))
                .stream(request(), LlmCallContext.defaults());
        stream.close();

        assertThat(stream.hasNext()).isFalse();
        assertThatThrownBy(stream::next).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void fallsBackToEchoWhenScriptIsExhausted() {
        LlmStream stream = new FakeLlmProvider().stream(request("你好"), LlmCallContext.defaults());

        drain(stream);

        assertThat(stream.response().orElseThrow().content())
                .containsExactly(new TextBlock("（Fake 模型）收到：你好"));
    }

    @Test
    void echoAcknowledgesImages() {
        UserMessage imageOnly = new UserMessage(List.of(
                new ImageBlock("att-1", "image/png", "a.png"),
                new ImageBlock("att-2", "image/png", "b.png")),
                Instant.parse("2026-10-21T00:00:00Z"));
        UserMessage withText = new UserMessage(List.of(
                new TextBlock("看图"),
                new ImageBlock("att-3", "image/png", "c.png")),
                Instant.parse("2026-10-21T00:00:00Z"));

        LlmStream imageStream = new FakeLlmProvider()
                .stream(requestOf(imageOnly), LlmCallContext.defaults());
        LlmStream textStream = new FakeLlmProvider()
                .stream(requestOf(withText), LlmCallContext.defaults());

        assertThat(drainAndRespond(imageStream).content())
                .containsExactly(new TextBlock("（Fake 模型）我已经收到 2 张图片。"));
        assertThat(drainAndRespond(textStream).content())
                .containsExactly(new TextBlock("（Fake 模型）收到：看图（另附 1 张图片）"));
    }

    private List<LlmDelta> drain(LlmStream stream) {
        List<LlmDelta> deltas = new ArrayList<>();
        while (stream.hasNext()) {
            deltas.add(stream.next());
        }
        return deltas;
    }

    private LlmRequest request() {
        return request("hello");
    }

    private LlmRequest request(String text) {
        return requestOf(new UserMessage(List.of(new TextBlock(text)),
                Instant.parse("2026-10-21T00:00:00Z")));
    }

    private LlmRequest requestOf(UserMessage message) {
        ModelEndpoint endpoint = new ModelEndpoint(ProviderType.FAKE, "", "", "fake-coder",
                16_000, 2_000, ModelCapabilities.defaults());
        ModelProfile profile = ModelProfile.create(new ModelId("fake-default"), "Fake", endpoint);
        return new LlmRequest(profile, "", List.of(message), List.of(), ThinkingLevel.OFF);
    }

    private LlmResponse drainAndRespond(LlmStream stream) {
        drain(stream);
        return stream.response().orElseThrow();
    }
}
