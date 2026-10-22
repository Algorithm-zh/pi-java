package com.piagent.trigger.http.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.piagent.application.context.ManualCompactionView;
import com.piagent.application.context.CompactionApplicationService;
import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.SessionId;
import com.piagent.application.context.SkillApplicationService;
import com.piagent.domain.context.SkillDescriptor;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.trigger.http.ApiExceptionHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 技能与压缩控制器的 MockMvc 单元测试。
 *
 * <p>只验证 HTTP 契约：状态码、字段名与错误码映射；业务行为由应用层与端到端测试覆盖。</p>
 */
class ContextControllerTest {

    private static final String SESSION_ID = "01JAVA00000000000000000001";

    private final ObjectMapper json = new ObjectMapper();
    private SkillApplicationService skills;
    private CompactionApplicationService compactions;
    private MockMvc mvc;
    private SkillDescriptor deploy;

    @BeforeEach
    void setUp() {
        skills = mock(SkillApplicationService.class);
        compactions = mock(CompactionApplicationService.class);
        deploy = new SkillDescriptor("deploy", "发布流程", Path.of("skills/deploy/SKILL.md"), "/skills");
        mvc = MockMvcBuilders
                .standaloneSetup(new SkillController(skills), new CompactionController(compactions))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void listsSkills() throws Exception {
        when(skills.list(false)).thenReturn(List.of(deploy));

        MvcResult result = mvc.perform(get("/api/v1/skills")).andExpect(status().isOk()).andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(body).hasSize(1);
        assertThat(body.get(0).path("name").asText()).isEqualTo("deploy");
        assertThat(body.get(0).path("description").asText()).isEqualTo("发布流程");
        assertThat(body.get(0).path("source").asText()).isEqualTo("/skills");
    }

    @Test
    void refreshFlagIsForwarded() throws Exception {
        when(skills.list(true)).thenReturn(List.of());

        mvc.perform(get("/api/v1/skills").param("refresh", "true")).andExpect(status().isOk());
    }

    @Test
    void returnsSkillContent() throws Exception {
        when(skills.find("deploy")).thenReturn(Optional.of(deploy));
        when(skills.readContent("deploy")).thenReturn("第一步");

        MvcResult result = mvc.perform(get("/api/v1/skills/deploy/content"))
                .andExpect(status().isOk()).andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.path("name").asText()).isEqualTo("deploy");
        assertThat(body.path("content").asText()).isEqualTo("第一步");
    }

    @Test
    void unknownSkillMapsTo404() throws Exception {
        when(skills.find("missing")).thenReturn(Optional.empty());

        MvcResult result = mvc.perform(get("/api/v1/skills/missing"))
                .andExpect(status().isNotFound()).andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.path("code").asText()).isEqualTo("SKILL_NOT_FOUND");
    }

    @Test
    void compactReturnsAppliedResult() throws Exception {
        when(compactions.compact(SessionId.parse(SESSION_ID)))
                .thenReturn(ManualCompactionView.applied(
                        EntryId.parse("01JAVA00000000000000000002"),
                        1000, 200, "## 目标", false));

        MvcResult result = mvc.perform(post("/api/v1/sessions/{id}/compact", SESSION_ID))
                .andExpect(status().isOk()).andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.path("applied").asBoolean()).isTrue();
        assertThat(body.path("tokensBefore").asInt()).isEqualTo(1000);
        assertThat(body.path("tokensAfter").asInt()).isEqualTo(200);
        assertThat(body.path("savedTokens").asInt()).isEqualTo(800);
        assertThat(body.path("entryId").asText()).isEqualTo("01JAVA00000000000000000002");
    }

    @Test
    void compactSkipIsNotAnError() throws Exception {
        when(compactions.compact(SessionId.parse(SESSION_ID)))
                .thenReturn(ManualCompactionView.skipped(100, "会话还没有任何消息"));

        MvcResult result = mvc.perform(post("/api/v1/sessions/{id}/compact", SESSION_ID))
                .andExpect(status().isOk()).andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.path("applied").asBoolean()).isFalse();
        assertThat(body.path("message").asText()).contains("未执行压缩");
    }

    @Test
    void compactWhileRunningMapsToConflict() throws Exception {
        when(compactions.compact(SessionId.parse(SESSION_ID)))
                .thenThrow(new DomainException(DomainErrorCode.INVALID_STATE_TRANSITION, "会话运行中"));

        mvc.perform(post("/api/v1/sessions/{id}/compact", SESSION_ID))
                .andExpect(status().isConflict());
    }
}

