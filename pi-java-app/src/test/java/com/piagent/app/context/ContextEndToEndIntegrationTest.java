package com.piagent.app.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.piagent.app.PiJavaApplication;
import com.piagent.application.context.SkillApplicationService;
import com.piagent.application.session.command.CreateSessionCommand;
import com.piagent.application.session.service.SessionApplicationService;
import com.piagent.domain.model.ModelEndpoint;
import com.piagent.domain.model.ModelId;
import com.piagent.domain.model.ModelProfile;
import com.piagent.domain.model.port.ModelProfileRepository;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.ThinkingLevel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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
 * 技能与上下文压缩的端到端测试（真实 Spring MVC + Flyway + Fake 模型）。
 *
 * <p>验证：技能目录来自配置的磁盘目录；{@code /skill:} 在落库前被展开为正文；
 * {@code POST /sessions/{id}/compact} 在内容不足时给出明确业务结论而非报错；
 * 未知技能返回 404 {@code SKILL_NOT_FOUND}。</p>
 */
@SpringBootTest(classes = PiJavaApplication.class, properties = {
    "pi.fake.demo-mode=true",
    "pi.agent.skills[0]=target/context-e2e-skills"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ContextEndToEndIntegrationTest {

    private static final String MODEL_ID = "context-e2e-fake";
    private static final Duration ASYNC_TIMEOUT = Duration.ofSeconds(30);
    private static final Path SKILL_ROOT = Path.of("target/context-e2e-skills")
            .toAbsolutePath().normalize();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SessionApplicationService sessions;

    @Autowired
    private SkillApplicationService skills;

    @Autowired
    private ModelProfileRepository models;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    private Path workspace;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM pi_approval_request");
        jdbc.update("DELETE FROM pi_session_entry");
        jdbc.update("DELETE FROM pi_session");
        jdbc.update("DELETE FROM pi_tool_policy");
        jdbc.update("DELETE FROM pi_model_profile");
        workspace = Path.of("target/context-e2e-workspace").toAbsolutePath().normalize();
        Files.createDirectories(workspace);
        models.insert(ModelProfile.create(new ModelId(MODEL_ID), "Context E2E Fake",
                ModelEndpoint.fake(MODEL_ID)));
        Path deploy = SKILL_ROOT.resolve("deploy");
        Files.createDirectories(deploy);
        Files.writeString(deploy.resolve("SKILL.md"), """
                ---
                name: deploy
                description: 打包与发布流程
                ---
                # 发布流程
                第一步：执行 mvn -q package
                """, StandardCharsets.UTF_8);
        skills.list(true);
    }

    @Test
    void listsSkillsFromConfiguredDirectory() throws Exception {
        mockMvc.perform(get("/api/v1/skills"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name=='deploy')].description")
                        .value("打包与发布流程"));
    }

    @Test
    void returnsSkillContentWithoutFrontmatter() throws Exception {
        mockMvc.perform(get("/api/v1/skills/deploy/content"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", containsString("第一步：执行 mvn -q package")))
                .andExpect(jsonPath("$.content", not(containsString("description:"))));
    }

    @Test
    void returns404ForUnknownSkill() throws Exception {
        mockMvc.perform(get("/api/v1/skills/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SKILL_NOT_FOUND"));
    }

    @Test
    void expandsSkillCommandBeforePersisting() throws Exception {
        ChatSession session = createSession("技能指令");
        String body = stream(Map.of("text", "/skill:deploy 发布 1.2.0"), session.identity().id());

        assertThat(body).contains("event:run.completed");
        List<SessionEntry> branch = sessions.listBranch(session.identity().id(), null);
        assertThat(branch.get(0).payload().toString())
                .contains("第一步：执行 mvn -q package")
                .contains("本次任务：发布 1.2.0");
    }

    @Test
    void manualCompactionReportsWhyItSkipped() throws Exception {
        ChatSession session = createSession("手动压缩");
        stream(Map.of("text", "你好"), session.identity().id());

        mockMvc.perform(post("/api/v1/sessions/{id}/compact", session.identity().id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(false))
                .andExpect(jsonPath("$.message", containsString("未执行压缩")));
    }

    @Test
    void manualCompactionOnEmptySessionIsNotAnError() throws Exception {
        ChatSession session = createSession("空会话压缩");

        mockMvc.perform(post("/api/v1/sessions/{id}/compact", session.identity().id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(false))
                .andExpect(jsonPath("$.message", containsString("还没有任何消息")));
    }

    private ChatSession createSession(String title) {
        return sessions.createSession(new CreateSessionCommand(title, workspace, MODEL_ID,
                ThinkingLevel.OFF));
    }

    private String stream(Map<String, String> body, SessionId sessionId) throws Exception {
        MvcResult streaming = mockMvc.perform(
                        post("/api/v1/sessions/{id}/messages/stream", sessionId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(body)
                                        .getBytes(StandardCharsets.UTF_8)))
                .andExpect(request().asyncStarted())
                .andReturn();
        streaming.getAsyncResult(ASYNC_TIMEOUT.toMillis());
        return mockMvc.perform(asyncDispatch(streaming))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
