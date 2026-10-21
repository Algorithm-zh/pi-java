package com.piagent.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.domain.model.llm.LlmRequest;
import com.piagent.domain.model.llm.LlmResponse;
import com.piagent.domain.model.llm.LlmTool;
import com.piagent.domain.session.model.ThinkingLevel;
import com.piagent.domain.session.model.message.AssistantStopReason;
import com.piagent.domain.session.model.message.TextBlock;
import com.piagent.domain.session.model.message.UserMessage;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class LlmContractTest {

    private final ModelProfile profile = ModelProfile.create(new ModelId("fake-default"), "Fake",
            ModelEndpoint.fake("fake-coder"));

    @Test
    void requestNormalizesNullSystemPromptAndFreezesLists() {
        List<LlmTool> tools = new java.util.ArrayList<>();
        tools.add(new LlmTool("read", "读取文件", "{\"type\":\"object\"}"));
        LlmRequest request = new LlmRequest(profile, null, List.of(userMessage()), tools,
                ThinkingLevel.OFF);

        tools.clear();

        assertThat(request.systemPrompt()).isEmpty();
        assertThat(request.tools()).hasSize(1);
        assertThat(request.messages()).hasSize(1);
    }

    @Test
    void requestRejectsDuplicateToolNames() {
        LlmTool tool = new LlmTool("read", "读取文件", "{\"type\":\"object\"}");

        assertThatThrownBy(() -> new LlmRequest(profile, "", List.of(userMessage()),
                List.of(tool, tool), ThinkingLevel.OFF))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.INVALID_ARGUMENT));
    }

    @Test
    void errorResponseRequiresMessage() {
        assertThat(LlmResponse.error(Usage.zero(), "boom").stopReason())
                .isEqualTo(AssistantStopReason.ERROR);
        assertThatThrownBy(() -> new LlmResponse(List.of(), Usage.zero(), AssistantStopReason.ERROR, null))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.INVALID_ARGUMENT));
    }

    @Test
    void usageRejectsNegativeAndComputesInputOutput() {
        assertThat(new Usage(10, 5, 0, 0, 15).inputAndOutput()).isEqualTo(15);
        assertThatThrownBy(() -> new Usage(-1, 0, 0, 0, 0))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.INVALID_ARGUMENT));
    }

    @Test
    void toolSchemaAndNameAreRequired() {
        assertThatThrownBy(() -> new LlmTool(" ", "desc", "{}"))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.INVALID_ARGUMENT));
        assertThatThrownBy(() -> new LlmTool("read", "desc", " "))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.INVALID_ARGUMENT));
    }

    private UserMessage userMessage() {
        return new UserMessage(List.of(new TextBlock("你好")), Instant.parse("2026-10-21T00:00:00Z"));
    }
}
