package com.piagent.app.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.piagent.app.PiJavaApplication;
import com.piagent.domain.model.ModelEndpoint;
import com.piagent.domain.model.ModelId;
import com.piagent.domain.model.ModelProfile;
import com.piagent.domain.model.port.ModelProfileRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 租户隔离集成测试：两个租户各自的请求只能看到自己的数据。
 *
 * <p>通过 HTTP 完成初始化、建租户与登录，再用真实令牌访问会话、审批、工具策略
 * 与附件接口；跨租户读取一律 404 或空列表，写入互不冲突。</p>
 */
@SpringBootTest(classes = PiJavaApplication.class, properties = {
    "spring.datasource.url=jdbc:h2:mem:pi_java_tenant_iso;MODE=MySQL;DATABASE_TO_LOWER=TRUE;"
            + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
    "pi.fake.demo-mode=true"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantIsolationIntegrationTest {

    private static final String MODEL_ID = "tenant-iso-fake";
    private static final String ADMIN_A = "iso-admin-a";
    private static final String ADMIN_B = "iso-admin-b";
    private static final String PASSWORD = "iso-password-123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ModelProfileRepository models;

    @Autowired
    private JdbcTemplate jdbc;

    private Path workspaceA;
    private Path workspaceB;
    private String tokenA;
    private String tokenB;
    private String sessionA;
    private String sessionB;
    private String attachmentA;

    @BeforeAll
    void setUpTenants() throws Exception {
        jdbc.update("DELETE FROM pi_auth_token");
        jdbc.update("DELETE FROM pi_app_user");
        jdbc.update("DELETE FROM pi_approval_request");
        jdbc.update("DELETE FROM pi_session_entry");
        jdbc.update("DELETE FROM pi_session");
        jdbc.update("DELETE FROM pi_tool_policy");
        jdbc.update("DELETE FROM pi_model_profile");
        workspaceA = Path.of("target/tenant-iso/alpha").toAbsolutePath().normalize();
        workspaceB = Path.of("target/tenant-iso/beta").toAbsolutePath().normalize();
        Files.createDirectories(workspaceA);
        Files.createDirectories(workspaceB);
        models.insert(ModelProfile.create(new ModelId(MODEL_ID), "Tenant Iso Fake",
                ModelEndpoint.fake(MODEL_ID)));

        tokenA = setupAdmin();
        createTenantB();
        tokenB = login(ADMIN_B, "team-b");

        sessionA = createSession(tokenA, "租户 A 会话", workspaceA);
        sessionB = createSession(tokenB, "租户 B 会话", workspaceB);
        attachmentA = uploadImage(tokenA);
    }

    @Test
    void sessionsAndRunsAreScopedToTenant() throws Exception {
        mockMvc.perform(get("/api/v1/sessions").header("Authorization", bearer(tokenB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(sessionB))
                .andExpect(jsonPath("$[?(@.id == '" + sessionA + "')]").isEmpty());
        mockMvc.perform(get("/api/v1/sessions/{id}", sessionA)
                        .header("Authorization", bearer(tokenB)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/sessions/{id}/run", sessionA)
                        .header("Authorization", bearer(tokenB)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/sessions/{id}", sessionA)
                        .header("Authorization", bearer(tokenA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(sessionA));
    }

    @Test
    void approvalsAndRunsAreScopedToTenant() throws Exception {
        MvcResult streaming = mockMvc.perform(post("/api/v1/sessions/{id}/messages/stream", sessionA)
                        .header("Authorization", bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("text", "/write iso-approved.txt 租户审批"))))
                .andExpect(request().asyncStarted())
                .andReturn();

        JsonNode pending = awaitPending();
        String approvalId = pending.get("approvalId").asText();
        String runId = pending.get("runId").asText();

        mockMvc.perform(get("/api/v1/approvals")
                        .param("sessionId", sessionA)
                        .header("Authorization", bearer(tokenB)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/approvals/{id}", approvalId)
                        .header("Authorization", bearer(tokenB)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APPROVAL_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/runs/{id}", runId)
                        .header("Authorization", bearer(tokenB)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
        mockMvc.perform(post("/api/v1/approvals/{id}/decision", approvalId)
                        .header("Authorization", bearer(tokenB))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("decision", "APPROVE", "reason", "越权"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APPROVAL_NOT_FOUND"));

        mockMvc.perform(post("/api/v1/approvals/{id}/decision", approvalId)
                        .header("Authorization", bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("decision", "DENY", "reason", "本租户拒绝"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DENIED"))
                .andExpect(jsonPath("$.decidedBy").value(ADMIN_A));
        mockMvc.perform(get("/api/v1/runs/{id}", runId)
                        .header("Authorization", bearer(tokenA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(sessionA));

        streaming.getAsyncResult(Duration.ofSeconds(30).toMillis());
        mockMvc.perform(asyncDispatch(streaming)).andExpect(status().isOk());
    }

    @Test
    void toolPoliciesAreScopedToTenant() throws Exception {
        mockMvc.perform(put("/api/v1/tool-policies")
                        .header("Authorization", bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("scope", "USER", "toolName", "read",
                                "effect", "DENY", "approvalMode", "ONCE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effect").value("DENY"));

        mockMvc.perform(get("/api/v1/tool-policies")
                        .param("cwd", workspaceB.toString())
                        .header("Authorization", bearer(tokenB)))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));

        mockMvc.perform(put("/api/v1/tool-policies")
                        .header("Authorization", bearer(tokenB))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("scope", "USER", "toolName", "read",
                                "effect", "ALLOW", "approvalMode", "ONCE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effect").value("ALLOW"));

        mockMvc.perform(get("/api/v1/tool-policies")
                        .param("cwd", workspaceA.toString())
                        .header("Authorization", bearer(tokenA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].effect").value("DENY"));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM pi_tool_policy WHERE tool_name = 'read'", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void attachmentsAreScopedToTenant() throws Exception {
        mockMvc.perform(get("/api/v1/attachments/stats")
                        .header("Authorization", bearer(tokenB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
        mockMvc.perform(get("/api/v1/attachments/{id}", attachmentA)
                        .header("Authorization", bearer(tokenB)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ATTACHMENT_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/attachments/{id}", attachmentA)
                        .header("Authorization", bearer(tokenA)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.IMAGE_PNG));
    }

    @Test
    void tenantAdminEndpointsRequireAdminRole() throws Exception {
        Map<String, Object> member = new HashMap<>();
        member.put("username", "iso-member-a");
        member.put("displayName", "普通成员");
        member.put("password", PASSWORD);
        member.put("role", "MEMBER");
        mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(member)))
                .andExpect(status().isCreated());
        String memberToken = login("iso-member-a", null);
        mockMvc.perform(get("/api/v1/tenants").header("Authorization", bearer(memberToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
        mockMvc.perform(get("/api/v1/tenants").header("Authorization", bearer(tokenB)))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("team-b")));
    }

    private String setupAdmin() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/auth/setup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", ADMIN_A, "displayName", "租户 A 管理员",
                                "password", PASSWORD))))
                .andExpect(status().isCreated())
                .andReturn();
        return tokenOf(created);
    }

    private void createTenantB() throws Exception {
        mockMvc.perform(post("/api/v1/tenants")
                        .header("Authorization", bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("slug", "team-b", "name", "团队 B",
                                "username", ADMIN_B, "displayName", "租户 B 管理员",
                                "password", PASSWORD))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").value("team-b"));
    }

    private String login(String username, String tenantSlug) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("username", username);
        body.put("password", PASSWORD);
        if (tenantSlug != null) {
            body.put("tenantSlug", tenantSlug);
        }
        MvcResult session = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isOk())
                .andReturn();
        return tokenOf(session);
    }

    private String createSession(String token, String title, Path cwd) throws Exception {
        JsonNode created = objectMapper.readTree(mockMvc.perform(post("/api/v1/sessions")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", title, "cwd", cwd.toString(),
                                "modelProfileId", MODEL_ID, "thinkingLevel", "OFF"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        return created.get("id").asText();
    }

    private JsonNode awaitPending() throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            String json = mockMvc.perform(get("/api/v1/approvals")
                            .param("sessionId", sessionA)
                            .param("status", "PENDING")
                            .header("Authorization", bearer(tokenA)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            JsonNode list = objectMapper.readTree(json);
            if (list.isArray() && !list.isEmpty()) {
                return list.get(0);
            }
            Thread.sleep(100);
        }
        throw new AssertionError("等待审批待办超时: " + sessionA);
    }

    private String uploadImage(String token) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "iso.png", MediaType.IMAGE_PNG_VALUE,
                new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 42});
        JsonNode uploaded = objectMapper.readTree(mockMvc.perform(
                        multipart("/api/v1/attachments").file(file)
                                .header("Authorization", bearer(token)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        return uploaded.get("id").asText();
    }

    private String tokenOf(MvcResult result) throws Exception {
        JsonNode body = objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        String token = body.path("token").asText();
        assertThat(token).isNotBlank();
        return token;
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private byte[] json(Map<String, ?> body) throws Exception {
        return objectMapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
    }
}
