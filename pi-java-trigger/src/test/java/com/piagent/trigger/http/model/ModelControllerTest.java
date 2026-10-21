package com.piagent.trigger.http.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.piagent.application.model.command.CreateModelProfileCommand;
import com.piagent.application.model.command.UpdateModelProfileCommand;
import com.piagent.application.model.service.ModelProfileApplicationService;
import com.piagent.domain.model.ModelCapabilities;
import com.piagent.domain.model.ModelEndpoint;
import com.piagent.domain.model.ModelId;
import com.piagent.domain.model.ModelProfile;
import com.piagent.domain.model.ProviderType;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.trigger.http.ApiExceptionHandler;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ModelControllerTest {

    private final ObjectMapper json = new ObjectMapper();
    private ModelProfileApplicationService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(ModelProfileApplicationService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ModelController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void listsProfilesWithoutSecrets() throws Exception {
        when(service.listAll()).thenReturn(List.of(profile(false)));

        MvcResult result = mvc.perform(get("/api/v1/models"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(body).hasSize(1);
        assertThat(body.get(0).path("id").asText()).isEqualTo("deepseek-chat");
        assertThat(body.get(0).path("endpoint").path("apiKeyEnv").asText())
                .isEqualTo("DEEPSEEK_API_KEY");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("sk-");
    }

    @Test
    void createsProfileAndMapsEndpoint() throws Exception {
        when(service.create(any())).thenReturn(profile(false));

        MvcResult result = mvc.perform(post("/api/v1/models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isCreated())
                .andReturn();

        ArgumentCaptor<CreateModelProfileCommand> captor =
                ArgumentCaptor.forClass(CreateModelProfileCommand.class);
        verify(service).create(captor.capture());
        assertThat(captor.getValue().modelId()).isEqualTo("deepseek-chat");
        assertThat(captor.getValue().endpoint().provider()).isEqualTo(ProviderType.OPENAI_COMPATIBLE);
        assertThat(captor.getValue().endpoint().contextWindow()).isEqualTo(64_000);
        JsonNode body = json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.path("version").asLong()).isEqualTo(1);
    }

    @Test
    void updatesProfileUsingPathId() throws Exception {
        when(service.update(any())).thenReturn(profile(false).withVersion(2));

        mvc.perform(put("/api/v1/models/deepseek-chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"DeepSeek V3","endpoint":%s}
                                """.formatted(endpointJson())))
                .andExpect(status().isOk());

        ArgumentCaptor<UpdateModelProfileCommand> captor =
                ArgumentCaptor.forClass(UpdateModelProfileCommand.class);
        verify(service).update(captor.capture());
        assertThat(captor.getValue().modelId()).isEqualTo("deepseek-chat");
        assertThat(captor.getValue().displayName()).isEqualTo("DeepSeek V3");
    }

    @Test
    void togglesProfile() throws Exception {
        when(service.enable("deepseek-chat")).thenReturn(profile(false));
        when(service.disable("deepseek-chat")).thenReturn(profile(true).disable());

        mvc.perform(post("/api/v1/models/deepseek-chat/enable")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/models/deepseek-chat/disable")).andExpect(status().isOk());

        verify(service).enable("deepseek-chat");
        verify(service).disable("deepseek-chat");
    }

    @Test
    void mapsNotFoundTo404() throws Exception {
        when(service.find("missing"))
                .thenThrow(new DomainException(DomainErrorCode.MODEL_NOT_FOUND, "模型档案不存在"));

        MvcResult result = mvc.perform(get("/api/v1/models/missing"))
                .andExpect(status().isNotFound())
                .andReturn();

        assertThat(errorCode(result)).isEqualTo("MODEL_NOT_FOUND");
    }

    @Test
    void mapsDuplicateTo409() throws Exception {
        when(service.create(any()))
                .thenThrow(new DomainException(DomainErrorCode.MODEL_ALREADY_EXISTS, "模型档案已存在"));

        MvcResult result = mvc.perform(post("/api/v1/models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isConflict())
                .andReturn();

        assertThat(errorCode(result)).isEqualTo("MODEL_ALREADY_EXISTS");
    }

    @Test
    void unknownProviderIsBadRequest() throws Exception {
        String body = createBody().replace("OPENAI_COMPATIBLE", "ALIEN");

        MvcResult result = mvc.perform(post("/api/v1/models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertThat(errorCode(result)).isEqualTo("INVALID_ARGUMENT");
    }

    @Test
    void malformedJsonIsBadRequest() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertThat(errorCode(result)).isEqualTo("INVALID_ARGUMENT");
    }

    private String createBody() {
        return """
                {"id":"deepseek-chat","displayName":"DeepSeek","endpoint":%s}
                """.formatted(endpointJson());
    }

    private String endpointJson() {
        return """
                {"provider":"OPENAI_COMPATIBLE","baseUrl":"https://api.deepseek.com/v1",
                 "apiKeyEnv":"DEEPSEEK_API_KEY","modelName":"deepseek-chat",
                 "contextWindow":64000,"maxOutputTokens":8000,
                 "capabilities":{"streaming":true,"toolCalls":true,"thinking":false}}
                """;
    }

    private ModelProfile profile(boolean disabled) {
        ModelEndpoint endpoint = new ModelEndpoint(ProviderType.OPENAI_COMPATIBLE,
                "https://api.deepseek.com/v1", "DEEPSEEK_API_KEY", "deepseek-chat", 64_000, 8_000,
                ModelCapabilities.defaults());
        ModelProfile profile = ModelProfile.create(new ModelId("deepseek-chat"), "DeepSeek", endpoint);
        return disabled ? profile.disable() : profile;
    }

    private String errorCode(MvcResult result) throws Exception {
        JsonNode body = json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        return body.path("code").asText();
    }
}
