package com.piagent.trigger.http.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.piagent.application.agent.command.SendMessageCommand;
import com.piagent.application.agent.readmodel.RunHandle;
import com.piagent.application.agent.service.AgentLoopService;
import com.piagent.application.session.command.CreateSessionCommand;
import com.piagent.application.session.service.SessionApplicationService;
import com.piagent.domain.agent.RunId;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.SessionConfig;
import com.piagent.domain.session.model.SessionId;
import com.piagent.trigger.http.ApiExceptionHandler;
import com.piagent.trigger.http.run.RunStreamService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 会话控制器测试：DTO 映射、状态码与 SSE 入口接线。
 */
class SessionControllerTest {

    private static final SessionId SESSION_ID = SessionId.parse("01TST000000000000000000001");
    private static final RunId RUN_ID = RunId.parse("01TST000000000000000000002");
    private static final Path WORKSPACE = Path.of(System.getProperty("java.io.tmpdir"), "pi-test-workspace");

    private final ObjectMapper json = new ObjectMapper();
    private SessionApplicationService sessions;
    private AgentLoopService agentLoop;
    private RunStreamService streams;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        sessions = mock(SessionApplicationService.class);
        agentLoop = mock(AgentLoopService.class);
        streams = mock(RunStreamService.class);
        mvc = MockMvcBuilders.standaloneSetup(
                        new SessionController(sessions, agentLoop, streams))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void createsSessionFromRequest() throws Exception {
        when(sessions.createSession(any())).thenReturn(session());

        MvcResult result = mvc.perform(post("/api/v1/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"测试会话","cwd":"D:/demo",
                                 "modelProfileId":"fake-demo","thinkingLevel":"LOW"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode body = bodyOf(result);
        assertThat(body.path("id").asText()).isEqualTo(SESSION_ID.toString());
        assertThat(body.path("status").asText()).isEqualTo("IDLE");
        assertThat(body.path("thinkingLevel").asText()).isEqualTo("OFF");

        ArgumentCaptor<CreateSessionCommand> captor =
                ArgumentCaptor.forClass(CreateSessionCommand.class);
        verify(sessions).createSession(captor.capture());
        assertThat(captor.getValue().title()).isEqualTo("测试会话");
        assertThat(captor.getValue().cwd()).isEqualTo(Path.of("D:/demo"));
        assertThat(captor.getValue().modelProfileId()).isEqualTo("fake-demo");
        assertThat(captor.getValue().thinkingLevel()).hasToString("LOW");
    }

    @Test
    void listsAndFindsSessions() throws Exception {
        when(sessions.list(WORKSPACE, 20)).thenReturn(List.of(session()));
        when(sessions.find(SESSION_ID)).thenReturn(session());

        MvcResult listResult = mvc.perform(get("/api/v1/sessions")
                        .param("cwd", WORKSPACE.toString()).param("limit", "20"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(bodyOf(listResult).get(0).path("id").asText())
                .isEqualTo(SESSION_ID.toString());

        MvcResult detailResult = mvc.perform(get("/api/v1/sessions/{id}", SESSION_ID))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(bodyOf(detailResult).path("cwd").asText()).isEqualTo(WORKSPACE.toString());
    }

    @Test
    void streamsMessageAndOpensRunChannel() throws Exception {
        when(agentLoop.start(any())).thenReturn(new RunHandle(RUN_ID, SESSION_ID));
        when(streams.open(RUN_ID, 0)).thenReturn(new SseEmitter(0L));

        mvc.perform(post("/api/v1/sessions/{id}/messages/stream", SESSION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"/read README.md\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<SendMessageCommand> captor =
                ArgumentCaptor.forClass(SendMessageCommand.class);
        verify(agentLoop).start(captor.capture());
        assertThat(captor.getValue().sessionId()).isEqualTo(SESSION_ID);
        assertThat(captor.getValue().text()).isEqualTo("/read README.md");
        verify(streams).open(RUN_ID, 0);
    }

    @Test
    void returnsActiveRunOrNoContent() throws Exception {
        when(agentLoop.findActive(SESSION_ID))
                .thenReturn(Optional.of(new RunHandle(RUN_ID, SESSION_ID)));

        MvcResult result = mvc.perform(get("/api/v1/sessions/{id}/run", SESSION_ID))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(bodyOf(result).path("runId").asText()).isEqualTo(RUN_ID.toString());

        when(agentLoop.findActive(SESSION_ID)).thenReturn(Optional.empty());
        mvc.perform(get("/api/v1/sessions/{id}/run", SESSION_ID))
                .andExpect(status().isNoContent());
    }

    @Test
    void abortsWithAcceptedStatus() throws Exception {
        when(agentLoop.abort(SESSION_ID)).thenReturn(new RunHandle(RUN_ID, SESSION_ID));

        MvcResult result = mvc.perform(post("/api/v1/sessions/{id}/abort", SESSION_ID))
                .andExpect(status().isAccepted())
                .andReturn();
        assertThat(bodyOf(result).path("runId").asText()).isEqualTo(RUN_ID.toString());
    }

    private JsonNode bodyOf(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static ChatSession session() {
        return ChatSession.createRoot(SESSION_ID, "测试会话",
                SessionConfig.forWorkspace(WORKSPACE, "fake-demo"));
    }
}
