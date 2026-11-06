package com.piagent.trigger.http.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.piagent.application.timeline.SessionBranchComparison;
import com.piagent.application.timeline.SessionBranchComparisonService;
import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.trigger.http.ApiExceptionHandler;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 分叉对比控制器测试：DTO 映射、参数解析与 409 映射。 */
class SessionComparisonControllerTest {

    private static final SessionId SESSION_ID = SessionId.parse("01TST000000000000000000001");
    private static final EntryId BRIEF = EntryId.parse("01TST000000000000000000002");
    private static final EntryId FORK = EntryId.parse("01TST000000000000000000003");
    private static final EntryId CURRENT_LEAF = EntryId.parse("01TST000000000000000000004");

    private final ObjectMapper json = new ObjectMapper();
    private SessionBranchComparisonService comparisons;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        comparisons = mock(SessionBranchComparisonService.class);
        mvc = MockMvcBuilders.standaloneSetup(new SessionComparisonController(comparisons))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void returnsBothSidesAndFileGroups() throws Exception {
        when(comparisons.compare(SESSION_ID, null)).thenReturn(comparison());

        MvcResult result = mvc.perform(get("/api/v1/sessions/{id}/comparison", SESSION_ID))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = bodyOf(result);
        assertThat(body.path("available").asBoolean()).isTrue();
        assertThat(body.path("rewindEntryId").asText()).isEqualTo(BRIEF.toString());
        assertThat(body.path("forkEntryId").asText()).isEqualTo(FORK.toString());
        assertThat(body.path("at").asText()).isEqualTo("2026-11-06T13:20:00Z");
        assertThat(body.path("abandoned").path("intents").get(0).asText()).isEqualTo("修复登录");
        assertThat(body.path("abandoned").path("runs").asInt()).isEqualTo(1);
        assertThat(body.path("abandoned").path("approvals").path("denied").asInt()).isEqualTo(1);
        assertThat(body.path("abandoned").path("verifications").path("failed").asInt())
                .isEqualTo(1);
        assertThat(body.path("abandoned").path("plan").path("unfinished").get(0).asText())
                .isEqualTo("补测试");
        assertThat(body.path("abandoned").path("files").get(0).path("path").asText())
                .isEqualTo("A.java");
        assertThat(body.path("current").path("intents").get(0).asText()).isEqualTo("新方案");
        assertThat(body.path("files").path("onlyAbandoned").get(0).asText())
                .isEqualTo("A.java");
        assertThat(body.path("files").path("onlyCurrent").get(0).asText())
                .isEqualTo("B.java");
        assertThat(body.path("files").path("truncated").asBoolean()).isFalse();
        assertThat(body.path("summary").asText()).contains("旧分支");
    }

    @Test
    void parsesRewindParameter() throws Exception {
        when(comparisons.compare(SESSION_ID, BRIEF)).thenReturn(comparison());

        mvc.perform(get("/api/v1/sessions/{id}/comparison", SESSION_ID)
                        .param("rewind", BRIEF.toString()))
                .andExpect(status().isOk());

        verify(comparisons).compare(SESSION_ID, BRIEF);
    }

    @Test
    void returnsUnavailableWithoutRewind() throws Exception {
        when(comparisons.compare(SESSION_ID, null))
                .thenReturn(SessionBranchComparison.unavailable());

        MvcResult result = mvc.perform(get("/api/v1/sessions/{id}/comparison", SESSION_ID))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = bodyOf(result);
        assertThat(body.path("available").asBoolean()).isFalse();
        assertThat(body.path("summary").asText()).isEmpty();
        assertThat(body.path("abandoned").path("intents").isEmpty()).isTrue();
    }

    @Test
    void mapsInvalidBranchToConflict() throws Exception {
        when(comparisons.compare(SESSION_ID, BRIEF))
                .thenThrow(new DomainException(DomainErrorCode.INVALID_BRANCH,
                        "指定的回退简报不在当前分支上"));

        MvcResult result = mvc.perform(get("/api/v1/sessions/{id}/comparison", SESSION_ID)
                        .param("rewind", BRIEF.toString()))
                .andExpect(status().isConflict())
                .andReturn();

        assertThat(bodyOf(result).path("code").asText()).isEqualTo("INVALID_BRANCH");
    }

    private static SessionBranchComparison comparison() {
        SessionBranchComparison.ApprovalStats approvals = new SessionBranchComparison.ApprovalStats(
                1, 1, List.of("write"));
        SessionBranchComparison.VerificationStats verifications =
                new SessionBranchComparison.VerificationStats(1, 1, List.of("mvn test"));
        SessionBranchComparison.BranchSide abandoned = new SessionBranchComparison.BranchSide(
                FORK, List.of("修复登录"), 1, 1, 1, 2, List.of("COMPLETED"), approvals,
                verifications, new SessionBranchComparison.PlanStats(2, 1, List.of("补测试")),
                new SessionBranchComparison.ChangeStats(1, 1, 10, 2),
                List.of(new SessionBranchComparison.FileSummary("A.java", 10, 2, 1)));
        SessionBranchComparison.BranchSide current = new SessionBranchComparison.BranchSide(
                CURRENT_LEAF,
                List.of("新方案"), 1, 1, 1, 0, List.of("COMPLETED"),
                SessionBranchComparison.ApprovalStats.empty(),
                SessionBranchComparison.VerificationStats.empty(),
                SessionBranchComparison.PlanStats.empty(),
                new SessionBranchComparison.ChangeStats(1, 1, 3, 1),
                List.of(new SessionBranchComparison.FileSummary("B.java", 3, 1, 1)));
        return new SessionBranchComparison(true, BRIEF, FORK,
                Instant.parse("2026-11-06T13:20:00Z"), abandoned, current,
                new SessionBranchComparison.FileDifference(List.of("A.java"), List.of("B.java"),
                        List.of(), false),
                "旧分支：1 轮对话；当前分支：1 轮对话。");
    }

    private JsonNode bodyOf(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }
}