package com.piagent.trigger.http.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.piagent.application.governance.command.UpsertToolPolicyCommand;
import com.piagent.application.governance.readmodel.ToolPolicyView;
import com.piagent.application.governance.service.ToolPolicyApplicationService;
import com.piagent.domain.governance.model.ApprovalMode;
import com.piagent.domain.governance.model.PolicyEffect;
import com.piagent.domain.governance.model.PolicyScope;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.tool.model.ToolRisk;
import com.piagent.trigger.http.ApiExceptionHandler;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 工具策略 HTTP 控制器测试。
 *
 * <p>覆盖查询、upsert 命令转换、默认风险省略、非法枚举与未知工具映射。</p>
 */
class ToolPolicyControllerTest {

    private final ObjectMapper json = new ObjectMapper();
    private ToolPolicyApplicationService service;
    private MockMvc mvc;
    private ToolPolicyView view;

    @BeforeEach
    void setUp() {
        service = mock(ToolPolicyApplicationService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ToolPolicyController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
        view = new ToolPolicyView(7L, PolicyScope.WORKSPACE, "GLOBAL", "bash",
                PolicyEffect.ASK, ApprovalMode.SESSION, ToolRisk.CRITICAL, 30_000,
                Map.of("allowNetwork", false), 3L);
    }

    @Test
    void listsWorkspacePolicies() throws Exception {
        when(service.list("D:/demo")).thenReturn(List.of(view));

        MvcResult result = mvc.perform(get("/api/v1/tool-policies").param("cwd", "D:/demo"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(body).hasSize(1);
        assertThat(body.get(0).path("toolName").asText()).isEqualTo("bash");
        assertThat(body.get(0).path("approvalMode").asText()).isEqualTo("SESSION");
        assertThat(body.get(0).path("config").path("allowNetwork").asBoolean()).isFalse();
        assertThat(body.get(0).path("version").asLong()).isEqualTo(3);
        verify(service).list("D:/demo");
    }

    @Test
    void upsertsPolicyAndParsesEnums() throws Exception {
        when(service.upsert(any())).thenReturn(view);

        MvcResult result = mvc.perform(put("/api/v1/tool-policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scope":"workspace","cwd":"D:/demo","toolName":"bash",
                                 "effect":"ask","approvalMode":"session",
                                 "riskLevel":"critical","timeoutMs":30000,
                                 "config":{"allowNetwork":false}}
                                """))
                .andExpect(status().isOk())
                .andReturn();

        ArgumentCaptor<UpsertToolPolicyCommand> captor =
                ArgumentCaptor.forClass(UpsertToolPolicyCommand.class);
        verify(service).upsert(captor.capture());
        UpsertToolPolicyCommand command = captor.getValue();
        assertThat(command.scope()).isEqualTo(PolicyScope.WORKSPACE);
        assertThat(command.cwd()).isEqualTo("D:/demo");
        assertThat(command.toolName()).isEqualTo("bash");
        assertThat(command.effect()).isEqualTo(PolicyEffect.ASK);
        assertThat(command.approvalMode()).isEqualTo(ApprovalMode.SESSION);
        assertThat(command.riskLevel()).isEqualTo(ToolRisk.CRITICAL);
        assertThat(command.timeoutMs()).isEqualTo(30_000);
        assertThat(command.config()).containsEntry("allowNetwork", false);
        JsonNode body = json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.path("version").asLong()).isEqualTo(3);
    }

    @Test
    void omitsRiskLevelToKeepToolDefault() throws Exception {
        when(service.upsert(any())).thenReturn(view);

        mvc.perform(put("/api/v1/tool-policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scope":"USER","toolName":"bash","effect":"ALLOW",
                                 "approvalMode":"ALWAYS"}
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<UpsertToolPolicyCommand> captor =
                ArgumentCaptor.forClass(UpsertToolPolicyCommand.class);
        verify(service).upsert(captor.capture());
        assertThat(captor.getValue().riskLevel()).isNull();
        assertThat(captor.getValue().config()).isEmpty();
    }

    @Test
    void rejectsUnknownScope() throws Exception {
        mvc.perform(put("/api/v1/tool-policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scope":"GLOBAL","toolName":"bash","effect":"ALLOW",
                                 "approvalMode":"ONCE"}
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void rejectsMissingToolName() throws Exception {
        MvcResult result = mvc.perform(put("/api/v1/tool-policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scope":"USER","effect":"ALLOW","approvalMode":"ONCE"}
                                """))
                .andExpect(status().isBadRequest())
                .andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.path("code").asText()).isEqualTo("INVALID_ARGUMENT");
        verifyNoInteractions(service);
    }

    @Test
    void rejectsNullConfigValue() throws Exception {
        mvc.perform(put("/api/v1/tool-policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scope":"USER","toolName":"bash","effect":"ALLOW",
                                 "approvalMode":"ONCE","config":{"flag":null}}
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void mapsUnknownToolTo404() throws Exception {
        when(service.upsert(any())).thenThrow(new DomainException(
                DomainErrorCode.TOOL_NOT_FOUND, "工具不存在: bash2"));

        mvc.perform(put("/api/v1/tool-policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scope":"USER","toolName":"bash2","effect":"ALLOW",
                                 "approvalMode":"ONCE"}
                                """))
                .andExpect(status().isNotFound());
    }
}
