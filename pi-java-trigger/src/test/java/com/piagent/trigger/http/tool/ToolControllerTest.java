package com.piagent.trigger.http.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.piagent.application.tool.readmodel.ToolView;
import com.piagent.application.tool.service.ToolCatalogApplicationService;
import com.piagent.domain.governance.model.PolicyEffect;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.tool.model.ExecutionMode;
import com.piagent.domain.tool.model.ToolRisk;
import com.piagent.trigger.http.ApiExceptionHandler;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ToolControllerTest {

    private final ObjectMapper json = new ObjectMapper();
    private ToolCatalogApplicationService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(ToolCatalogApplicationService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ToolController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void listsToolsWithPolicyEffect() throws Exception {
        when(service.list("D:/demo")).thenReturn(List.of(new ToolView("read", "读取文件",
                "{\"type\":\"object\"}", ToolRisk.LOW, ExecutionMode.PARALLEL, 10_000,
                PolicyEffect.ALLOW)));

        MvcResult result = mvc.perform(get("/api/v1/tools").param("cwd", "D:/demo"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(body).hasSize(1);
        assertThat(body.get(0).path("name").asText()).isEqualTo("read");
        assertThat(body.get(0).path("effect").asText()).isEqualTo("ALLOW");
        assertThat(body.get(0).path("requiresApproval").asBoolean()).isFalse();
        assertThat(body.get(0).path("inputSchema").asText()).contains("object");
        verify(service).list("D:/demo");
    }

    @Test
    void cwdIsOptional() throws Exception {
        when(service.list(null)).thenReturn(List.of());

        mvc.perform(get("/api/v1/tools")).andExpect(status().isOk());

        verify(service).list(null);
    }

    @Test
    void mapsInvalidCwdToBadRequest() throws Exception {
        when(service.list("bad"))
                .thenThrow(new DomainException(DomainErrorCode.INVALID_ARGUMENT, "cwd 不合法"));

        MvcResult result = mvc.perform(get("/api/v1/tools").param("cwd", "bad"))
                .andExpect(status().isBadRequest())
                .andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.path("code").asText()).isEqualTo("INVALID_ARGUMENT");
    }
}
