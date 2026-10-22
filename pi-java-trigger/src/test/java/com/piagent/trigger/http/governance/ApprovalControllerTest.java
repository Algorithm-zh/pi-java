package com.piagent.trigger.http.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.piagent.application.governance.service.ApprovalApplicationService;
import com.piagent.domain.agent.RunId;
import com.piagent.domain.governance.model.ApprovalArguments;
import com.piagent.domain.governance.model.ApprovalContext;
import com.piagent.domain.governance.model.ApprovalDecision;
import com.piagent.domain.governance.model.ApprovalId;
import com.piagent.domain.governance.model.ApprovalRequest;
import com.piagent.domain.governance.model.ApprovalStatus;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.shared.Ulid;
import com.piagent.domain.tool.model.ToolCallId;
import com.piagent.domain.tool.model.ToolName;
import com.piagent.domain.tool.model.ToolRisk;
import com.piagent.trigger.http.ApiExceptionHandler;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 审批 HTTP 控制器测试。
 *
 * <p>覆盖列表过滤、脱敏参数、决策者固定、非法枚举与 404/409 映射；
 * 审批状态机本身由领域测试与集成测试覆盖。</p>
 */
class ApprovalControllerTest {

    private static final Instant NOW = Instant.parse("2026-10-22T08:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(30);

    private final ObjectMapper json = new ObjectMapper();
    private ApprovalApplicationService service;
    private MockMvc mvc;
    private SessionId sessionId;
    private ApprovalRequest pending;

    @BeforeEach
    void setUp() {
        service = mock(ApprovalApplicationService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ApprovalController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
        sessionId = new SessionId(Ulid.generate(NOW));
        pending = pending();
    }

    @Test
    void listsApprovalsWithRedactedArguments() throws Exception {
        when(service.findBySession(sessionId, ApprovalStatus.PENDING))
                .thenReturn(List.of(pending));

        MvcResult result = mvc.perform(get("/api/v1/approvals")
                        .param("sessionId", sessionId.toString())
                        .param("status", "pending"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(body).hasSize(1);
        JsonNode approval = body.get(0);
        assertThat(approval.path("approvalId").asText()).isEqualTo(pending.id().toString());
        assertThat(approval.path("sessionId").asText()).isEqualTo(sessionId.toString());
        assertThat(approval.path("toolName").asText()).isEqualTo("write");
        assertThat(approval.path("riskLevel").asText()).isEqualTo("HIGH");
        assertThat(approval.path("status").asText()).isEqualTo("PENDING");
        assertThat(approval.path("arguments").path("token").asText()).isEqualTo("***");
        assertThat(approval.path("argumentsDigest").asText()).hasSize(64);
        assertThat(approval.path("decidedAt").isNull()).isTrue();
        verify(service).findBySession(sessionId, ApprovalStatus.PENDING);
    }

    @Test
    void rejectsMissingSessionId() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/approvals"))
                .andExpect(status().isBadRequest())
                .andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.path("code").asText()).isEqualTo("INVALID_ARGUMENT");
        verifyNoInteractions(service);
    }

    @Test
    void rejectsUnknownStatus() throws Exception {
        mvc.perform(get("/api/v1/approvals")
                        .param("sessionId", sessionId.toString())
                        .param("status", "waiting"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void decidesPendingApprovalAsLocalUser() throws Exception {
        ApprovalRequest approved = pending.approve("local-user", "ok", NOW.plusSeconds(12));
        when(service.decide(pending.id(), ApprovalDecision.APPROVE, "local-user", "ok"))
                .thenReturn(approved);

        MvcResult result = mvc.perform(post("/api/v1/approvals/{id}/decision", pending.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"approve\",\"reason\":\"  ok  \"}"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.path("status").asText()).isEqualTo("APPROVED");
        assertThat(body.path("decidedBy").asText()).isEqualTo("local-user");
        verify(service).decide(pending.id(), ApprovalDecision.APPROVE, "local-user", "ok");
    }

    @Test
    void rejectsUnknownDecision() throws Exception {
        mvc.perform(post("/api/v1/approvals/{id}/decision", pending.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"MAYBE\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void mapsTerminalDecisionToConflict() throws Exception {
        when(service.decide(pending.id(), ApprovalDecision.DENY, "local-user", null))
                .thenThrow(new DomainException(DomainErrorCode.APPROVAL_INVALID_STATE,
                        "审批已是终态"));

        MvcResult result = mvc.perform(post("/api/v1/approvals/{id}/decision", pending.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"DENY\"}"))
                .andExpect(status().isConflict())
                .andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.path("code").asText()).isEqualTo("APPROVAL_INVALID_STATE");
    }

    @Test
    void mapsMissingApprovalTo404() throws Exception {
        when(service.find(pending.id()))
                .thenThrow(new DomainException(DomainErrorCode.APPROVAL_NOT_FOUND, "审批不存在"));

        mvc.perform(get("/api/v1/approvals/{id}", pending.id()))
                .andExpect(status().isNotFound());
    }

    private ApprovalRequest pending() {
        ApprovalContext context = new ApprovalContext(sessionId, new RunId(Ulid.generate(NOW)),
                new ToolCallId("call-1"), new ToolName("write"),
                new ApprovalArguments("b".repeat(64),
                        Map.of("path", "a.txt", "token", "***")),
                ToolRisk.HIGH);
        return ApprovalRequest.pending(new ApprovalId(Ulid.generate(NOW)), context, NOW, TTL);
    }
}
