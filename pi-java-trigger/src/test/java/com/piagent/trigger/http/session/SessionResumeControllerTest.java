package com.piagent.trigger.http.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.piagent.application.timeline.BranchResumeResult;
import com.piagent.application.timeline.SessionBranchResumeService;
import com.piagent.domain.git.model.GitConflict;
import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.trigger.http.ApiExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 分支切换控制器测试：预览、执行、冲突、不可用与错误映射。
 */
class SessionResumeControllerTest {

    private static final SessionId SESSION_ID = SessionId.parse("01TST000000000000000000001");
    private static final EntryId BRIEF = EntryId.parse("01TST000000000000000000002");
    private static final EntryId TARGET = EntryId.parse("01TST000000000000000000003");

    private final ObjectMapper json = new ObjectMapper();
    private SessionBranchResumeService resumes;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        resumes = mock(SessionBranchResumeService.class);
        mvc = MockMvcBuilders.standaloneSetup(new SessionResumeController(resumes))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void defaultsToPreviewAndReturnsFacts() throws Exception {
        when(resumes.resume(SESSION_ID, BRIEF, true, null)).thenReturn(preview());

        MvcResult result = mvc.perform(post("/api/v1/sessions/{id}/resume", SESSION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"branchEntryId\":\"" + BRIEF + "\"}"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = bodyOf(result);
        assertThat(body.path("dryRun").asBoolean()).isTrue();
        assertThat(body.path("available").asBoolean()).isTrue();
        assertThat(body.path("targetLeafId").asText()).isEqualTo(TARGET.toString());
        assertThat(body.path("files").get(0).asText()).isEqualTo("a.txt");
        assertThat(body.path("leftEntries").asInt()).isEqualTo(5);
        assertThat(body.path("targetRuns").asInt()).isEqualTo(2);
    }

    @Test
    void executesWhenDryRunIsFalse() throws Exception {
        when(resumes.resume(SESSION_ID, BRIEF, false, "先跑测试")).thenReturn(executed());

        MvcResult result = mvc.perform(post("/api/v1/sessions/{id}/resume", SESSION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"branchEntryId\":\"" + BRIEF
                                + "\",\"dryRun\":false,\"note\":\"先跑测试\"}"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = bodyOf(result);
        assertThat(body.path("switched").asBoolean()).isTrue();
        assertThat(body.path("briefEntryId").asText())
                .isEqualTo("01TST000000000000000000004");
        assertThat(body.path("restored").get(0).asText()).isEqualTo("a.txt");
        verify(resumes).resume(SESSION_ID, BRIEF, false, "先跑测试");
    }

    @Test
    void returnsConflictWith200() throws Exception {
        when(resumes.resume(SESSION_ID, BRIEF, false, null)).thenReturn(conflict());

        JsonNode body = bodyOf(mvc.perform(post("/api/v1/sessions/{id}/resume", SESSION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"branchEntryId\":\"" + BRIEF + "\",\"dryRun\":false}"))
                .andExpect(status().isOk()).andReturn());

        assertThat(body.path("restorable").asBoolean()).isFalse();
        assertThat(body.path("conflicts").get(0).path("path").asText()).isEqualTo("a.txt");
    }

    @Test
    void returnsUnavailableWith200() throws Exception {
        when(resumes.resume(SESSION_ID, BRIEF, true, null)).thenReturn(unavailable());

        JsonNode body = bodyOf(mvc.perform(post("/api/v1/sessions/{id}/resume", SESSION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"branchEntryId\":\"" + BRIEF + "\"}"))
                .andExpect(status().isOk()).andReturn());

        assertThat(body.path("available").asBoolean()).isFalse();
        assertThat(body.path("unavailableReason").asText())
                .contains("目标分支没有可用的工作区快照");
    }

    @Test
    void mapsInvalidBranchToConflict() throws Exception {
        when(resumes.resume(SESSION_ID, BRIEF, true, null))
                .thenThrow(new DomainException(DomainErrorCode.INVALID_BRANCH, "目标不是分支简报"));

        MvcResult result = mvc.perform(post("/api/v1/sessions/{id}/resume", SESSION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"branchEntryId\":\"" + BRIEF + "\"}"))
                .andExpect(status().isConflict())
                .andReturn();

        assertThat(bodyOf(result).path("code").asText()).isEqualTo("INVALID_BRANCH");
    }

    @Test
    void rejectsMissingBranchEntryId() throws Exception {
        mvc.perform(post("/api/v1/sessions/{id}/resume", SESSION_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    private static BranchResumeResult preview() {
        return new BranchResumeResult(true, false, true, true, "", TARGET, List.of("a.txt"),
                List.of(), List.of(), null, 5, 1, 8, 2, "切回另一条分支：…");
    }

    private static BranchResumeResult executed() {
        return new BranchResumeResult(false, true, true, true, "", TARGET, List.of("a.txt"),
                List.of("a.txt"), List.of(), EntryId.parse("01TST000000000000000000004"),
                5, 1, 8, 2, "切回另一条分支：…");
    }

    private static BranchResumeResult conflict() {
        return new BranchResumeResult(false, false, true, false, "", TARGET, List.of("a.txt"),
                List.of(), List.of(new GitConflict("a.txt", "又变了")), null, 5, 1, 8, 2,
                "切回另一条分支：…");
    }

    private static BranchResumeResult unavailable() {
        return new BranchResumeResult(false, false, false, false, "目标分支没有可用的工作区快照",
                TARGET, List.of(), List.of(), List.of(), null, 5, 1, 8, 2, "");
    }

    private JsonNode bodyOf(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }
}
