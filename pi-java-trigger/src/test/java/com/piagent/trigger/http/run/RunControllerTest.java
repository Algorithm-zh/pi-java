package com.piagent.trigger.http.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.piagent.application.agent.service.AgentLoopService;
import com.piagent.application.agent.service.RunEventHub;
import com.piagent.domain.agent.AgentRun;
import com.piagent.domain.agent.RunBudget;
import com.piagent.domain.agent.RunId;
import com.piagent.domain.agent.RunStopReason;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.TokenUsage;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.trigger.http.ApiExceptionHandler;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 运行控制器测试：快照映射、错误码与 SSE 重放游标解析。
 */
class RunControllerTest {

    private static final SessionId SESSION_ID = SessionId.parse("01TST000000000000000000001");
    private static final RunId RUN_ID = RunId.parse("01TST000000000000000000002");
    private static final Instant STARTED = Instant.parse("2026-10-22T00:00:00Z");

    private final ObjectMapper json = new ObjectMapper();
    private AgentLoopService agentLoop;
    private RunEventHub hub;
    private RunStreamService streams;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        agentLoop = mock(AgentLoopService.class);
        hub = mock(RunEventHub.class);
        streams = mock(RunStreamService.class);
        mvc = MockMvcBuilders.standaloneSetup(new RunController(agentLoop, hub, streams))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void returnsRunSnapshot() throws Exception {
        when(agentLoop.findRun(RUN_ID)).thenReturn(Optional.of(finishedRun()));

        MvcResult result = mvc.perform(get("/api/v1/runs/{runId}", RUN_ID))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = bodyOf(result);
        assertThat(body.path("runId").asText()).isEqualTo(RUN_ID.toString());
        assertThat(body.path("sessionId").asText()).isEqualTo(SESSION_ID.toString());
        assertThat(body.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(body.path("stopReason").asText()).isEqualTo("STOP");
        assertThat(body.path("turns").asInt()).isEqualTo(1);
        assertThat(body.path("toolCalls").asInt()).isZero();
        assertThat(body.path("usage").path("totalTokens").asLong()).isEqualTo(15);
        assertThat(body.path("durationMs").asLong()).isEqualTo(2_000);
        assertThat(body.path("finishedAt").asText()).isEqualTo("2026-10-22T00:00:02Z");
    }

    @Test
    void returnsNotFoundForUnknownRun() throws Exception {
        when(agentLoop.findRun(RUN_ID)).thenReturn(Optional.empty());

        MvcResult result = mvc.perform(get("/api/v1/runs/{runId}", RUN_ID))
                .andExpect(status().isNotFound())
                .andReturn();
        assertThat(bodyOf(result).path("code").asText()).isEqualTo("RUN_NOT_FOUND");
    }

    @Test
    void parsesReplayCursor() {
        assertThat(RunController.parseSeq(null)).isZero();
        assertThat(RunController.parseSeq(" ")).isZero();
        assertThat(RunController.parseSeq("12")).isEqualTo(12);
        assertThat(RunController.parseSeq(RUN_ID + ":12")).isEqualTo(12);
        assertThat(RunController.parseSeq("run:7:3")).isEqualTo(3);

        assertThatThrownBy(() -> RunController.parseSeq("abc"))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.INVALID_ARGUMENT));
        assertThatThrownBy(() -> RunController.parseSeq("-1"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void opensEventStreamAfterCursor() throws Exception {
        when(hub.exists(RUN_ID)).thenReturn(true);
        when(streams.open(RUN_ID, 7)).thenReturn(new SseEmitter(0L));

        mvc.perform(get("/api/v1/runs/{runId}/events", RUN_ID)
                        .header("Last-Event-ID", RUN_ID + ":7"))
                .andExpect(status().isOk());

        verify(streams).open(RUN_ID, 7);
    }

    @Test
    void rejectsEventsForUnknownRun() throws Exception {
        when(hub.exists(RUN_ID)).thenReturn(false);

        MvcResult result = mvc.perform(get("/api/v1/runs/{runId}/events", RUN_ID))
                .andExpect(status().isNotFound())
                .andReturn();
        assertThat(bodyOf(result).path("code").asText()).isEqualTo("RUN_NOT_FOUND");
    }

    private JsonNode bodyOf(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static AgentRun finishedRun() {
        return AgentRun.start(RUN_ID, SESSION_ID, RunBudget.defaults(), STARTED)
                .recordTurn(new TokenUsage(10, 5, 0, 0, 15))
                .finish(RunStopReason.STOP, null, STARTED.plusSeconds(2));
    }
}
