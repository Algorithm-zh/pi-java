package com.piagent.app.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.piagent.app.PiJavaApplication;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 鉴权 HTTP 全链路测试：初始化、登录、状态、登出与错误映射。
 *
 * <p>使用独立的内存库保证初始化状态从零开始；所有请求都经过真实的
 * {@code AuthFilter}，同时覆盖 Cookie 与 Bearer 两种令牌载体。</p>
 */
@SpringBootTest(classes = PiJavaApplication.class, properties = {
    "spring.datasource.url=jdbc:h2:mem:pi_java_auth_http;MODE=MySQL;DATABASE_TO_LOWER=TRUE;"
            + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
    "pi.fake.demo-mode=true"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthHttpIntegrationTest {

    private static final String USERNAME = "auth-http-admin";
    private static final String PASSWORD = "auth-http-password";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @Order(1)
    void unauthenticatedApiRequestsAreRejectedBeforeSetup() throws Exception {
        mockMvc.perform(get("/api/v1/auth/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.initialized").value(false))
                .andExpect(jsonPath("$.authenticated").value(false));
        mockMvc.perform(get("/api/v1/sessions"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    @Test
    @Order(2)
    void setupCreatesAdminIssuesCookieAndRejectsRepeatedSetup() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/auth/setup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", USERNAME, "displayName", "初始化管理员",
                                "password", PASSWORD))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.username").value(USERNAME))
                .andExpect(jsonPath("$.user.tenantSlug").value("default"))
                .andExpect(jsonPath("$.user.role").value("ADMIN"))
                .andExpect(cookie().httpOnly("pi_session", true))
                .andExpect(header().string("Set-Cookie",
                        org.hamcrest.Matchers.containsString("SameSite=Strict")))
                .andReturn();
        String token = tokenOf(created);

        mockMvc.perform(get("/api/v1/auth/state").cookie(new Cookie("pi_session", token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.initialized").value(true))
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.user.username").value(USERNAME));

        mockMvc.perform(post("/api/v1/auth/setup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", "second-admin", "password", PASSWORD))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUTH_ALREADY_INITIALIZED"));
    }

    @Test
    @Order(3)
    void loginRejectsWrongPasswordAndAcceptsBearerToken() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("tenantSlug", "default", "username", USERNAME,
                                "password", "wrong-password"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));

        MvcResult loggedIn = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("tenantSlug", "default", "username", USERNAME,
                                "password", PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.username").value(USERNAME))
                .andReturn();
        String token = tokenOf(loggedIn);

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(USERNAME))
                .andExpect(jsonPath("$.tenantSlug").value("default"));
        mockMvc.perform(get("/api/v1/tenants").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    @Order(4)
    void logoutRevokesTokenAndClearsCookie() throws Exception {
        String token = tokenOf(mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", USERNAME, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn());

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge("pi_session", 0));

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/sessions").cookie(new Cookie("pi_session", token)))
                .andExpect(status().isUnauthorized());
    }

    private String tokenOf(MvcResult result) throws Exception {
        JsonNode body = objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        String token = body.path("token").asText();
        assertThat(token).isNotBlank();
        return token;
    }

    private byte[] json(Map<String, ?> body) throws Exception {
        return objectMapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
    }
}
