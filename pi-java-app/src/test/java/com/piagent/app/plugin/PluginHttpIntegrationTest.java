package com.piagent.app.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.piagent.app.PiJavaApplication;
import com.piagent.app.plugin.fixture.SamplePlugin;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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

/**
 * 插件 HTTP 生命周期集成测试。
 *
 * <p>使用真实 Spring 上下文、H2 数据库与动态插件 jar，验证安装只落库、
 * 启用后工具出现在目录、停用后消失，以及错误码到 HTTP 状态的映射。</p>
 */
@SpringBootTest(classes = PiJavaApplication.class)
@ActiveProfiles("test")
@AutoConfigureMockMvc
class PluginHttpIntegrationTest {

    private static final String PLUGINS = "/api/v1/plugins";
    private static final String TOOLS = "/api/v1/tools";

    private static Path jar;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeAll
    static void buildPluginJar() throws IOException {
        jar = SamplePluginJar.create(Path.of("target/plugin-http"));
    }

    @BeforeEach
    void cleanPlugins() {
        jdbc.update("DELETE FROM pi_plugin");
    }

    @Test
    void installsEnablesAndDisablesPlugin() throws Exception {
        assertThat(tools()).doesNotContain(SamplePlugin.TOOL);

        mockMvc.perform(post(PLUGINS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(installRequest()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(SamplePlugin.ID))
                .andExpect(jsonPath("$.status").value("INSTALLED"))
                .andExpect(jsonPath("$.jarPath").isNotEmpty());

        assertThat(tools()).doesNotContain(SamplePlugin.TOOL);

        mockMvc.perform(post(PLUGINS + "/{id}/enable", SamplePlugin.ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        assertThat(tools()).contains(SamplePlugin.TOOL);

        mockMvc.perform(post(PLUGINS + "/{id}/disable", SamplePlugin.ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));

        assertThat(tools()).doesNotContain(SamplePlugin.TOOL);
    }

    @Test
    void rejectsDuplicateInstallAndUnknownPlugin() throws Exception {
        mockMvc.perform(post(PLUGINS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(installRequest()))
                .andExpect(status().isCreated());

        mockMvc.perform(post(PLUGINS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(installRequest()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLUGIN_ALREADY_EXISTS"));

        mockMvc.perform(post(PLUGINS + "/{id}/enable", "missing.plugin"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLUGIN_NOT_FOUND"));
    }

    @Test
    void rejectsMissingJar() throws Exception {
        String body = mapper.writeValueAsString(
                Map.of("jarPath", Path.of("target/absent-plugin.jar").toAbsolutePath().toString()));

        mockMvc.perform(post(PLUGINS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PLUGIN_LOAD_FAILED"));
    }

    private String tools() throws Exception {
        return mockMvc.perform(get(TOOLS))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String installRequest() throws IOException {
        return mapper.writeValueAsString(Map.of("jarPath", jar.toString()));
    }
}
