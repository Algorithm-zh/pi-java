package com.piagent.infrastructure.llm.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.piagent.domain.model.ModelCapabilities;
import com.piagent.domain.model.ModelEndpoint;
import com.piagent.domain.model.ModelId;
import com.piagent.domain.model.ModelProfile;
import com.piagent.domain.model.llm.LlmErrorCode;
import com.piagent.domain.model.llm.LlmGatewayException;
import com.piagent.domain.model.llm.LlmRequest;
import com.piagent.domain.model.llm.LlmTool;
import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.ThinkingLevel;
import com.piagent.domain.session.model.message.AssistantMessage;
import com.piagent.domain.session.model.message.AssistantStopReason;
import com.piagent.domain.session.model.message.BranchSummaryMessage;
import com.piagent.domain.session.model.message.CompactionSummaryMessage;
import com.piagent.domain.session.model.message.TextBlock;
import com.piagent.domain.session.model.message.ThinkingBlock;
import com.piagent.domain.session.model.message.ToolCallBlock;
import com.piagent.domain.session.model.message.ToolResultMessage;
import com.piagent.domain.session.model.message.UserMessage;
import com.piagent.domain.shared.Ulid;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenAiRequestMapperTest {

    private static final Instant NOW = Instant.parse("2026-10-21T00:00:00Z");

    private final ObjectMapper json = new ObjectMapper();
    private final OpenAiRequestMapper mapper = new OpenAiRequestMapper(json);

    @Test
    void mapsMessagesAndToolsToChatCompletionsRequest() throws Exception {
        String body = mapper.toJson(request());

        JsonNode root = json.readTree(body);
        assertThat(root.path("model").asText()).isEqualTo("test-model");
        assertThat(root.path("stream").asBoolean()).isTrue();
        assertThat(root.path("stream_options").path("include_usage").asBoolean()).isTrue();
        assertThat(root.path("max_tokens").asInt()).isEqualTo(1_000);
        JsonNode messages = root.path("messages");
        assertThat(messages).hasSize(6);
        assertThat(messages.get(0).path("role").asText()).isEqualTo("system");
        assertThat(messages.get(1).path("content").asText()).isEqualTo("hello");
        JsonNode assistant = messages.get(2);
        assertThat(assistant.path("content").asText()).isEqualTo("hi");
        assertThat(assistant.path("tool_calls").get(0).path("id").asText()).isEqualTo("call_1");
        assertThat(json.readTree(assistant.path("tool_calls").get(0)
                .path("function").path("arguments").asText()).path("path").asText()).isEqualTo("a.txt");
        assertThat(messages.get(3).path("role").asText()).isEqualTo("tool");
        assertThat(messages.get(3).path("tool_call_id").asText()).isEqualTo("call_1");
        assertThat(messages.get(4).path("content").asText()).startsWith("[历史摘要]");
        assertThat(messages.get(5).path("content").asText()).contains("branch summary");
        assertThat(root.path("tools").get(0).path("function").path("parameters")
                .path("type").asText()).isEqualTo("object");
        assertThat(body).doesNotContain("secret-thinking");
    }

    @Test
    void skipsEmptyAssistantMessages() throws Exception {
        AssistantMessage empty = new AssistantMessage(List.of(), "fake", "test-model",
                com.piagent.domain.session.model.TokenUsage.zero(), AssistantStopReason.STOP, null, NOW);
        LlmRequest request = new LlmRequest(profile(), "", List.of(userMessage(), empty),
                List.of(), ThinkingLevel.OFF);

        JsonNode messages = json.readTree(mapper.toJson(request)).path("messages");

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).path("role").asText()).isEqualTo("user");
    }

    @Test
    void invalidToolSchemaFailsBeforeNetwork() {
        LlmTool invalid = new LlmTool("read", "读取", "not-json");
        LlmRequest request = new LlmRequest(profile(), "", List.of(userMessage()), List.of(invalid),
                ThinkingLevel.OFF);

        assertThatThrownBy(() -> mapper.toJson(request))
                .isInstanceOfSatisfying(LlmGatewayException.class,
                        error -> assertThat(error.code()).isEqualTo(LlmErrorCode.INVALID_REQUEST));
    }

    private LlmRequest request() {
        AssistantMessage assistant = new AssistantMessage(List.of(
                new TextBlock("hi"),
                new ThinkingBlock("secret-thinking", null),
                new ToolCallBlock("call_1", "read", Map.of("path", "a.txt"))),
                "openai", "test-model", com.piagent.domain.session.model.TokenUsage.zero(),
                AssistantStopReason.TOOL_USE, null, NOW);
        ToolResultMessage result = new ToolResultMessage("call_1", "read",
                List.of(new TextBlock("file content")), false, Map.of(), NOW);
        CompactionSummaryMessage compaction = new CompactionSummaryMessage("compacted summary", 100, NOW);
        BranchSummaryMessage branch = new BranchSummaryMessage("branch summary",
                EntryId.parse(Ulid.generate(NOW).value()), NOW);
        LlmTool tool = new LlmTool("read", "读取文件", "{\"type\":\"object\",\"properties\":{}}");
        return new LlmRequest(profile(), "you are helpful",
                List.of(userMessage(), assistant, result, compaction, branch), List.of(tool),
                ThinkingLevel.OFF);
    }

    private UserMessage userMessage() {
        return new UserMessage(List.of(new TextBlock("hello")), NOW);
    }

    private ModelProfile profile() {
        ModelEndpoint endpoint = new ModelEndpoint(com.piagent.domain.model.ProviderType.OPENAI_COMPATIBLE,
                "https://example.com/v1", "TEST_API_KEY", "test-model", 8_000, 1_000,
                ModelCapabilities.defaults());
        return ModelProfile.create(new ModelId("test-model"), "Test", endpoint);
    }
}
