package com.piagent.app.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.piagent.app.PiJavaApplication;
import com.piagent.app.plugin.fixture.SamplePlugin;
import com.piagent.application.session.command.CreateSessionCommand;
import com.piagent.application.session.service.SessionApplicationService;
import com.piagent.domain.model.ModelEndpoint;
import com.piagent.domain.model.ModelId;
import com.piagent.domain.model.ModelProfile;
import com.piagent.domain.model.port.ModelProfileRepository;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.ThinkingLevel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 插件工具端到端测试。
 *
 * <p>走完整链路：HTTP 安装并启用插件 → Fake demo 模型发起工具调用 →
 * Agent Loop 执行插件工具 → SSE 输出结果；停用后新 run 立即看不到该工具。</p>
 */
@SpringBootTest(classes = PiJavaApplication.class,
        properties = "pi.fake.demo-mode=true")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class PluginEndToEndIntegrationTest {

    private static final String MODEL_ID = "plugin-e2e-fake";
    private static final Duration ASYNC_TIMEOUT = Duration.ofSeconds(20);

    private static Path jar;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SessionApplicationService sessions;

    @Autowired
    private ModelProfileRepository models;

    private Path workspace;

    @BeforeAll
    static void buildPluginJar() throws IOException {
        jar = SamplePluginJar.create(Path.of("target/plugin-e2e"));
    }

    @BeforeEach
    void setUp() throws IOException {
        jdbc.update("DELETE FROM pi_plugin");
        jdbc.update("DELETE FROM pi_session_entry");
        jdbc.update("DELETE FROM pi_session");
        jdbc.update("DELETE FROM pi_tool_policy");
        jdbc.update("DELETE FROM pi_model_profile");
        workspace = Path.of("target/plugin-e2e-workspace").toAbsolutePath().normalize();
        Files.createDirectories(workspace);
        models.insert(ModelProfile.create(new ModelId(MODEL_ID), "插件 E2E Fake",
                ModelEndpoint.fake(MODEL_ID)));
    }

    @Test
    void pluginToolRunsThroughAgentLoopAndDisappearsAfterDisable() throws Exception {
        installAndEnable();

        ChatSession session = sessions.createSession(new CreateSessionCommand(
                "插件 E2E", workspace, MODEL_ID, ThinkingLevel.OFF));

        String enabledRun = stream(session.identity().id(),
                "/tool " + SamplePlugin.TOOL + " {\"text\":\"你好\"}");
        assertThat(enabledRun)
                .contains("event:tool.completed")
                .contains("\"toolName\":\"" + SamplePlugin.TOOL + "\"")
                .contains("echo:你好")
                .contains("event:run.completed");

        mockMvc.perform(post("/api/v1/plugins/{id}/disable", SamplePlugin.ID))
                .andExpect(status().isOk());
        assertThat(tools()).doesNotContain(SamplePlugin.TOOL);

        String disabledRun = stream(session.identity().id(),
                "/tool " + SamplePlugin.TOOL + " {\"text\":\"再来一次\"}");
        assertThat(disabledRun)
                .contains("event:tool.completed")
                .contains("工具未注册")
                .contains("event:run.completed");
    }

    private void installAndEnable() throws Exception {
        mockMvc.perform(post("/api/v1/plugins")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("jarPath", jar.toString()))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/plugins/{id}/enable", SamplePlugin.ID))
                .andExpect(status().isOk());
    }

    private String stream(SessionId sessionId, String text) throws Exception {
        MvcResult streaming = mockMvc.perform(
                        post("/api/v1/sessions/{id}/messages/stream", sessionId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(mapper.writeValueAsString(Map.of("text", text))))
                .andExpect(request().asyncStarted())
                .andReturn();
        streaming.getAsyncResult(ASYNC_TIMEOUT.toMillis());
        return mockMvc.perform(asyncDispatch(streaming))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String tools() throws Exception {
        return mockMvc.perform(get("/api/v1/tools"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
