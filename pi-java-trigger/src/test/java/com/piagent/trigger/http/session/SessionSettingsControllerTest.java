package com.piagent.trigger.http.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.piagent.application.session.command.ChangeSessionModelCommand;
import com.piagent.application.session.command.RenameSessionCommand;
import com.piagent.application.session.service.SessionSettingsApplicationService;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.SessionConfig;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.ThinkingLevel;
import com.piagent.trigger.http.ApiExceptionHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 会话设置控制器测试：路径、状态码与可选思考级别解析。
 */
class SessionSettingsControllerTest {

    private static final SessionId SESSION_ID = SessionId.parse("01TST000000000000000000001");

    private final ObjectMapper json = new ObjectMapper();
    private SessionSettingsApplicationService settings;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        settings = mock(SessionSettingsApplicationService.class);
        mvc = MockMvcBuilders.standaloneSetup(new SessionSettingsController(settings))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void renamesSession() throws Exception {
        when(settings.rename(any())).thenReturn(session().rename("新标题"));

        MvcResult result = mvc.perform(patch("/api/v1/sessions/{id}", SESSION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"新标题\"}"))
                .andExpect(status().isOk())
                .andReturn();

        ArgumentCaptor<RenameSessionCommand> captor =
                ArgumentCaptor.forClass(RenameSessionCommand.class);
        verify(settings).rename(captor.capture());
        assertThat(captor.getValue().sessionId()).isEqualTo(SESSION_ID);
        assertThat(captor.getValue().title()).isEqualTo("新标题");
        assertThat(bodyOf(result).path("title").asText()).isEqualTo("新标题");
    }

    @Test
    void rejectsBlankTitle() throws Exception {
        mvc.perform(patch("/api/v1/sessions/{id}", SESSION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\" \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void changesModelWithOptionalThinkingLevel() throws Exception {
        when(settings.changeModel(any())).thenReturn(session().changeModel("fake-demo",
                ThinkingLevel.HIGH));

        MvcResult result = mvc.perform(put("/api/v1/sessions/{id}/model", SESSION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelProfileId\":\"fake-demo\",\"thinkingLevel\":\"high\"}"))
                .andExpect(status().isOk())
                .andReturn();

        ArgumentCaptor<ChangeSessionModelCommand> captor =
                ArgumentCaptor.forClass(ChangeSessionModelCommand.class);
        verify(settings).changeModel(captor.capture());
        assertThat(captor.getValue().modelProfileId()).isEqualTo("fake-demo");
        assertThat(captor.getValue().thinkingLevel()).isEqualTo(ThinkingLevel.HIGH);
        assertThat(bodyOf(result).path("thinkingLevel").asText()).isEqualTo("HIGH");
    }

    @Test
    void invalidThinkingLevelIsBadRequest() throws Exception {
        mvc.perform(put("/api/v1/sessions/{id}/model", SESSION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelProfileId\":\"fake-demo\",\"thinkingLevel\":\"ULTRA\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void archivesSession() throws Exception {
        when(settings.archive(SESSION_ID)).thenReturn(session().archive());

        MvcResult result = mvc.perform(post("/api/v1/sessions/{id}/archive", SESSION_ID))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(bodyOf(result).path("status").asText()).isEqualTo("ARCHIVED");
    }

    @Test
    void deletesSession() throws Exception {
        mvc.perform(delete("/api/v1/sessions/{id}", SESSION_ID))
                .andExpect(status().isNoContent());

        verify(settings).delete(SESSION_ID);
    }

    private JsonNode bodyOf(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static ChatSession session() {
        return ChatSession.createRoot(SESSION_ID, "测试会话",
                SessionConfig.forWorkspace(Path.of("D:/demo"), "fake-demo"));
    }
}
