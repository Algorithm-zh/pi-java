package com.piagent.app.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.piagent.app.PiJavaApplication;
import com.piagent.app.mcp.fixture.AppFakeMcpServer;
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
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * MCP 端到端测试：启动真实 stdio 服务器 → 工具进入目录 → Agent Loop 调用 → SSE 返回。
 */
@SpringBootTest(classes = PiJavaApplication.class, properties = "pi.fake.demo-mode=true")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class McpEndToEndIntegrationTest {

    private static final String MODEL_ID = "mcp-e2e-fake";
    private static final Duration ASYNC_TIMEOUT = Duration.ofSeconds(30);

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

    @DynamicPropertySource
    static void mcpProperties(DynamicPropertyRegistry registry) {
        registry.add("pi.mcp.servers[0].name", () -> "echo");
        registry.add("pi.mcp.servers[0].transport", () -> "stdio");
        registry.add("pi.mcp.servers[0].stdio.command", McpEndToEndIntegrationTest::javaCommand);
        registry.add("pi.mcp.servers[0].stdio.args[0]", () -> "-cp");
        registry.add("pi.mcp.servers[0].stdio.args[1]", McpEndToEndIntegrationTest::classpath);
        registry.add("pi.mcp.servers[0].stdio.args[2]", AppFakeMcpServer.class::getName);
        registry.add("pi.mcp.servers[0].timeouts.request", () -> "10s");
        registry.add("pi.mcp.servers[0].timeouts.discovery", () -> "10s");
        registry.add("pi.mcp.servers[0].risks.default-level", () -> "low");
    }

    @BeforeEach
    void setUp() throws IOException {
        jdbc.update("DELETE FROM pi_approval_request");
        jdbc.update("DELETE FROM pi_session_entry");
        jdbc.update("DELETE FROM pi_session");
        jdbc.update("DELETE FROM pi_tool_policy");
        jdbc.update("DELETE FROM pi_model_profile");
        workspace = Path.of("target/mcp-e2e-workspace").toAbsolutePath().normalize();
        Files.createDirectories(workspace);
        models.insert(ModelProfile.create(new ModelId(MODEL_ID), "MCP E2E Fake",
                ModelEndpoint.fake(MODEL_ID)));
    }

    @Test
    void mcpToolAppearsInCatalogAndRunsThroughAgentLoop() throws Exception {
        mockMvc.perform(get("/api/v1/tools").param("cwd", workspace.toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("echo.echo")));

        ChatSession session = sessions.createSession(new CreateSessionCommand(
                "MCP E2E", workspace, MODEL_ID, ThinkingLevel.OFF));
        String stream = stream(session.identity().id(),
                "/tool echo.echo {\"text\":\"你好\"}");

        assertThat(stream)
                .contains("event:tool.completed")
                .contains("\"toolName\":\"echo.echo\"")
                .contains("echo:你好")
                .contains("event:run.completed");
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

    private static String javaCommand() {
        String executable = System.getProperty("os.name", "").toLowerCase(Locale.ROOT)
                .contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }

    private static String classpath() {
        return System.getProperty("surefire.test.class.path",
                System.getProperty("java.class.path"));
    }
}
