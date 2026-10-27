package com.piagent.app.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.piagent.app.PiJavaApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * OpenAPI 契约冒烟测试。
 *
 * <p>确保文档端点可用、版本元信息正确，且 Web 与 CLI 共用的核心接口都出现在
 * 生成的契约里；接口重命名时这里会先失败，避免发布一份过期文档。</p>
 */
@SpringBootTest(classes = PiJavaApplication.class)
@ActiveProfiles("test")
@AutoConfigureMockMvc
class OpenApiDocumentIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper mapper;

    @Test
    void documentCoversCoreApiSurface() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode document = mapper.readTree(result.getResponse().getContentAsString());

        assertThat(document.at("/info/title").asText()).isEqualTo("pi-java");
        assertThat(document.at("/info/version").asText()).isEqualTo("0.2.0");
        assertThat(document.at("/info/license/name").asText()).isEqualTo("MIT License");

        JsonNode paths = document.get("paths");
        assertThat(paths).isNotNull();
        assertThat(paths.has("/api/v1/system/info")).isTrue();
        assertThat(paths.has("/api/v1/sessions")).isTrue();
        assertThat(paths.has("/api/v1/sessions/{id}")).isTrue();
        assertThat(paths.has("/api/v1/sessions/{id}/plan")).isTrue();
        assertThat(paths.has("/api/v1/sessions/{id}/messages/stream")).isTrue();
        assertThat(paths.has("/api/v1/runs/{runId}/events")).isTrue();
        assertThat(paths.has("/api/v1/models")).isTrue();
        assertThat(paths.has("/api/v1/tools")).isTrue();
        assertThat(paths.has("/api/v1/attachments")).isTrue();
        assertThat(paths.has("/api/v1/attachments/{id}")).isTrue();
        assertThat(document.at("/components/schemas").size()).isPositive();
    }

    @Test
    void swaggerUiIsServedLocally() throws Exception {
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/swagger-ui/index.html"));
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }
}
