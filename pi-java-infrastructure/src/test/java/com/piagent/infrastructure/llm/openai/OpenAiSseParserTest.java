package com.piagent.infrastructure.llm.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.piagent.domain.model.Usage;
import com.piagent.domain.model.llm.LlmErrorCode;
import com.piagent.domain.model.llm.LlmGatewayException;
import com.piagent.domain.model.llm.LlmResponse;
import com.piagent.domain.model.llm.ThinkingDelta;
import com.piagent.domain.session.model.message.AssistantStopReason;
import com.piagent.domain.session.model.message.TextBlock;
import com.piagent.domain.session.model.message.ThinkingBlock;
import com.piagent.domain.session.model.message.ToolCallBlock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * SSE 解析器的协议边界测试。
 *
 * <p>重点验证内容块顺序、usage 缺省补齐、非法 JSON、缺失工具 id，
 * 以及 finish_reason 与工具调用不一致时的防御。</p>
 */
class OpenAiSseParserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void ordersThinkingBeforeTextAndMapsLength() {
        OpenAiSseParser parser = new OpenAiSseParser(MAPPER);
        parser.accept("{\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"先想\"}}]}");
        parser.accept("{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"再答\"},"
                + "\"finish_reason\":\"length\"}]}");

        LlmResponse response = parser.finish();

        assertThat(response.content()).containsExactly(
                new ThinkingBlock("先想", null), new TextBlock("再答"));
        assertThat(response.stopReason()).isEqualTo(AssistantStopReason.LENGTH);
    }

    @Test
    void fillsTotalTokensWhenProviderOmitsIt() {
        OpenAiSseParser parser = new OpenAiSseParser(MAPPER);
        parser.accept("{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"}}],"
                + "\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":3}}");

        LlmResponse response = parser.finish();

        assertThat(response.usage()).isEqualTo(new Usage(9, 3, 0, 0, 12));
    }

    @Test
    void prefersLargestCacheReadSignal() {
        OpenAiSseParser parser = new OpenAiSseParser(MAPPER);
        parser.accept("{\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2,"
                + "\"prompt_cache_hit_tokens\":7,"
                + "\"prompt_tokens_details\":{\"cached_tokens\":4}}}");

        LlmResponse response = parser.finish();

        assertThat(response.usage().cacheRead()).isEqualTo(7);
    }

    @Test
    void mergesMultipleToolCallsByIndexRegardlessOfOrder() {
        OpenAiSseParser parser = new OpenAiSseParser(MAPPER);
        parser.accept("{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":1,"
                + "\"id\":\"call_b\",\"function\":{\"name\":\"ls\",\"arguments\":\"{}\"}}]}}]}");
        parser.accept("{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,"
                + "\"id\":\"call_a\",\"function\":{\"name\":\"read\","
                + "\"arguments\":\"{\\\"path\\\":\\\"a\\\"}\"}}]},"
                + "\"finish_reason\":\"tool_calls\"}]}");

        LlmResponse response = parser.finish();

        assertThat(response.content()).containsExactly(
                new ToolCallBlock("call_a", "read", Map.of("path", "a")),
                new ToolCallBlock("call_b", "ls", Map.of()));
        assertThat(response.stopReason()).isEqualTo(AssistantStopReason.TOOL_USE);
    }

    @Test
    void rejectsSsePayloadThatIsNotObject() {
        OpenAiSseParser parser = new OpenAiSseParser(MAPPER);

        assertThatThrownBy(() -> parser.accept("\"hello\""))
                .isInstanceOfSatisfying(LlmGatewayException.class,
                        error -> assertThat(error.code()).isEqualTo(LlmErrorCode.MALFORMED_RESPONSE));
    }

    @Test
    void rejectsEmptyStream() {
        OpenAiSseParser parser = new OpenAiSseParser(MAPPER);

        assertThatThrownBy(parser::finish)
                .isInstanceOfSatisfying(LlmGatewayException.class,
                        error -> assertThat(error.code()).isEqualTo(LlmErrorCode.MALFORMED_RESPONSE));
    }

    @Test
    void rejectsToolUseReasonWithoutToolCalls() {
        OpenAiSseParser parser = new OpenAiSseParser(MAPPER);
        parser.accept("{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"done\"},"
                + "\"finish_reason\":\"tool_calls\"}]}");

        assertThatThrownBy(parser::finish)
                .isInstanceOfSatisfying(LlmGatewayException.class,
                        error -> assertThat(error.code()).isEqualTo(LlmErrorCode.MALFORMED_RESPONSE));
    }

    @Test
    void rejectsToolCallWithoutId() {
        OpenAiSseParser parser = new OpenAiSseParser(MAPPER);
        parser.accept("{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,"
                + "\"function\":{\"name\":\"read\",\"arguments\":\"{}\"}}]}}]}");

        assertThatThrownBy(parser::finish)
                .isInstanceOfSatisfying(LlmGatewayException.class,
                        error -> assertThat(error.code()).isEqualTo(LlmErrorCode.MALFORMED_RESPONSE));
    }

    @Test
    void rejectsNonObjectToolArguments() {
        OpenAiSseParser parser = new OpenAiSseParser(MAPPER);
        parser.accept("{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,"
                + "\"id\":\"call_1\",\"function\":{\"name\":\"read\",\"arguments\":\"[1]\"}}]}}]}");

        assertThatThrownBy(parser::finish)
                .isInstanceOfSatisfying(LlmGatewayException.class,
                        error -> assertThat(error.code()).isEqualTo(LlmErrorCode.MALFORMED_RESPONSE));
    }

    @Test
    void emitsThinkingDeltaForReasoningField() {
        OpenAiSseParser parser = new OpenAiSseParser(MAPPER);

        assertThat(parser.accept("{\"choices\":[{\"index\":0,"
                + "\"delta\":{\"reasoning\":\"思考\"}}]}"))
                .containsExactly(new ThinkingDelta("思考"));
        assertThat(parser.finish().content()).containsExactly(new ThinkingBlock("思考", null));
    }

    @Test
    void multipleTextChunksAccumulateInOrder() {
        OpenAiSseParser parser = new OpenAiSseParser(MAPPER);

        parser.accept("{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"a\"}}]}");
        parser.accept("{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"b\"}}]}");

        LlmResponse response = parser.finish();
        assertThat(response.content()).containsExactly(new TextBlock("ab"));
        assertThat(response.stopReason()).isEqualTo(AssistantStopReason.STOP);
    }

    @Test
    void emptyToolArgumentsBecomeEmptyMap() {
        OpenAiSseParser parser = new OpenAiSseParser(MAPPER);
        parser.accept("{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,"
                + "\"id\":\"call_1\",\"function\":{\"name\":\"ls\",\"arguments\":\"\"}}]}}]}");

        LlmResponse response = parser.finish();
        assertThat(response.content()).isEqualTo(
                List.of(new ToolCallBlock("call_1", "ls", Map.of())));
    }
}
