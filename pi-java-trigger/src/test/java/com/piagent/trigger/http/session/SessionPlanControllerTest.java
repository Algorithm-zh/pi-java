package com.piagent.trigger.http.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.piagent.application.plan.SessionPlanService;
import com.piagent.application.plan.SessionPlanSnapshot;
import com.piagent.domain.plan.model.Plan;
import com.piagent.domain.plan.model.PlanItem;
import com.piagent.domain.plan.model.PlanItemStatus;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.payload.VerificationPayload;
import com.piagent.trigger.http.ApiExceptionHandler;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SessionPlanControllerTest {

    private static final SessionId SESSION_ID = SessionId.parse("01TST000000000000000000001");

    private final ObjectMapper json = new ObjectMapper();
    private SessionPlanService plans;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        plans = mock(SessionPlanService.class);
        mvc = MockMvcBuilders.standaloneSetup(new SessionPlanController(plans))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void returnsPlanAndLatestVerification() throws Exception {
        when(plans.snapshot(SESSION_ID)).thenReturn(new SessionPlanSnapshot(plan(),
                new VerificationPayload("run-1", "mvn -q test", false, 1, false, false,
                        1200, "Tests run: 1, Failures: 1")));

        MvcResult result = mvc.perform(get("/api/v1/sessions/{id}/plan", SESSION_ID))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = bodyOf(result);
        assertThat(body.path("hasPlan").asBoolean()).isTrue();
        assertThat(body.path("total").asInt()).isEqualTo(2);
        assertThat(body.path("completed").asLong()).isEqualTo(1);
        assertThat(body.path("items").get(0).path("content").asText()).isEqualTo("读代码");
        assertThat(body.path("items").get(0).path("status").asText()).isEqualTo("done");
        assertThat(body.path("items").get(1).path("status").asText()).isEqualTo("in_progress");
        JsonNode verification = body.path("verification");
        assertThat(verification.path("passed").asBoolean()).isFalse();
        assertThat(verification.path("exitCode").asInt()).isEqualTo(1);
        assertThat(verification.path("command").asText()).isEqualTo("mvn -q test");
        assertThat(verification.path("output").asText()).contains("Failures");
    }

    @Test
    void returnsEmptySnapshotWithoutAnyPlan() throws Exception {
        when(plans.snapshot(SESSION_ID))
                .thenReturn(new SessionPlanSnapshot(Plan.empty(), null));

        MvcResult result = mvc.perform(get("/api/v1/sessions/{id}/plan", SESSION_ID))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = bodyOf(result);
        assertThat(body.path("hasPlan").asBoolean()).isFalse();
        assertThat(body.path("items").size()).isZero();
        assertThat(body.path("verification").isNull()).isTrue();
    }

    private JsonNode bodyOf(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static Plan plan() {
        return new Plan(List.of(
                new PlanItem("读代码", PlanItemStatus.DONE),
                new PlanItem("改实现", PlanItemStatus.IN_PROGRESS)));
    }
}
