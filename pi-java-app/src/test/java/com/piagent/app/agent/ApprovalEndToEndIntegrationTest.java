package com.piagent.app.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.piagent.app.PiJavaApplication;
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
 * 审批链路的 H2 + SSE 端到端测试。
 *
 * <p>走真实 Spring MVC、Flyway、MyBatis 与 Fake demo 脚本：用户发送
 * {@code /write}，运行阻塞在审批门面；测试通过 HTTP 查询并决策审批，
 * 再校验 SSE 事件顺序、审批持久化与文件最终状态。</p>
 */
@SpringBootTest(classes = PiJavaApplication.class,
        properties = "pi.fake.demo-mode=true")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ApprovalEndToEndIntegrationTest {

    private static final String MODEL_ID = "approval-e2e-fake";
    private static final String DECIDER = "local-user";
    private static final Duration ASYNC_TIMEOUT = Duration.ofSeconds(30);
    private static final int POLL_ATTEMPTS = 100;
    private static final long POLL_MILLIS = 100;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SessionApplicationService sessions;

    @Autowired
    private ModelProfileRepository models;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    private Path workspace;

    @BeforeEach
    void setUp() throws IOException {
        jdbc.update("DELETE FROM pi_approval_request");
        jdbc.update("DELETE FROM pi_session_entry");
        jdbc.update("DELETE FROM pi_session");
        jdbc.update("DELETE FROM pi_tool_policy");
        jdbc.update("DELETE FROM pi_model_profile");
        workspace = Path.of("target/approval-e2e-workspace").toAbsolutePath().normalize();
        Files.createDirectories(workspace);
        Files.deleteIfExists(workspace.resolve("approved.txt"));
        Files.deleteIfExists(workspace.resolve("denied.txt"));
        models.insert(ModelProfile.create(new ModelId(MODEL_ID), "Approval E2E Fake",
                ModelEndpoint.fake(MODEL_ID)));
    }

    @Test
    void approvedWriteRunsToolAndStreamsBothApprovalEvents() throws Exception {
        ChatSession session = createSession("审批批准");
        MvcResult streaming = startWrite(session.identity().id(), "approved.txt", "你好审批");

        JsonNode pending = awaitPending(session.identity().id());
        assertThat(pending.get("toolName").asText()).isEqualTo("write");
        assertThat(pending.get("riskLevel").asText()).isEqualTo("HIGH");
        assertThat(pending.get("status").asText()).isEqualTo("PENDING");
        assertThat(pending.get("arguments").get("path").asText()).isEqualTo("approved.txt");
        assertThat(pending.get("arguments").get("content").asText()).isEqualTo("你好审批");
        assertThat(pending.get("argumentsDigest").asText()).matches("[0-9a-f]{64}");
        String approvalId = pending.get("approvalId").asText();

        decide(approvalId, "APPROVE", "端到端测试批准");

        String body = complete(streaming);
        assertThat(body).containsSubsequence(
                "event:run.started", "event:approval.requested", "event:approval.resolved",
                "event:tool.completed", "event:run.completed");
        assertThat(body).contains("\"toolName\":\"write\"", "\"status\":\"APPROVED\"");
        assertThat(body).doesNotContain("argumentsDigest");
        assertThat(Files.readString(workspace.resolve("approved.txt"), StandardCharsets.UTF_8))
                .isEqualTo("你好审批");
        assertThat(approvalRow(approvalId))
                .containsEntry("status", "APPROVED")
                .containsEntry("decided_by", DECIDER)
                .containsEntry("reason", "端到端测试批准");
    }

    @Test
    void deniedWriteIsNotExecutedAndReturnsToolError() throws Exception {
        ChatSession session = createSession("审批拒绝");
        MvcResult streaming = startWrite(session.identity().id(), "denied.txt", "不要写");

        JsonNode pending = awaitPending(session.identity().id());
        String approvalId = pending.get("approvalId").asText();

        decide(approvalId, "DENY", "端到端测试拒绝");

        String body = complete(streaming);
        assertThat(body).contains("event:approval.resolved", "\"status\":\"DENIED\"");
        assertThat(body).contains("\"isError\":true", "用户拒绝执行工具 write");
        assertThat(Files.exists(workspace.resolve("denied.txt"))).isFalse();
        assertThat(approvalRow(approvalId))
                .containsEntry("status", "DENIED")
                .containsEntry("decided_by", DECIDER);
    }

    private ChatSession createSession(String title) {
        return sessions.createSession(new CreateSessionCommand(title, workspace, MODEL_ID,
                ThinkingLevel.OFF));
    }

    private MvcResult startWrite(SessionId sessionId, String path, String content) throws Exception {
        String payload = objectMapper.writeValueAsString(
                Map.of("text", "/write " + path + " " + content));
        return mockMvc.perform(post("/api/v1/sessions/{id}/messages/stream", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload.getBytes(StandardCharsets.UTF_8)))
                .andExpect(request().asyncStarted())
                .andReturn();
    }

    private JsonNode awaitPending(SessionId sessionId) throws Exception {
        for (int attempt = 0; attempt < POLL_ATTEMPTS; attempt++) {
            String json = mockMvc.perform(get("/api/v1/approvals")
                            .param("sessionId", sessionId.toString())
                            .param("status", "PENDING"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            JsonNode list = objectMapper.readTree(json);
            if (list.isArray() && !list.isEmpty()) {
                return list.get(0);
            }
            Thread.sleep(POLL_MILLIS);
        }
        throw new AssertionError("等待审批待办超时: " + sessionId);
    }

    private void decide(String approvalId, String decision, String reason) throws Exception {
        String payload = objectMapper.writeValueAsString(
                Map.of("decision", decision, "reason", reason));
        mockMvc.perform(post("/api/v1/approvals/{id}/decision", approvalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decidedBy").value(DECIDER));
    }

    private String complete(MvcResult streaming) throws Exception {
        streaming.getAsyncResult(ASYNC_TIMEOUT.toMillis());
        return mockMvc.perform(asyncDispatch(streaming))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private Map<String, Object> approvalRow(String approvalId) {
        return jdbc.queryForMap(
                "SELECT status, decided_by, reason FROM pi_approval_request WHERE id = ?",
                approvalId);
    }
}
