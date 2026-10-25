package com.piagent.trigger.http.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.piagent.application.git.GitCheckpointService;
import com.piagent.application.git.GitUndoResult;
import com.piagent.application.git.GitUndoService;
import com.piagent.application.git.SessionGitChanges;
import com.piagent.domain.git.model.FileChange;
import com.piagent.domain.git.model.GitChangeKind;
import com.piagent.domain.git.model.GitConflict;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.payload.GitChangeSetPayload;
import com.piagent.domain.session.model.payload.GitCheckpointPayload;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.trigger.http.ApiExceptionHandler;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 工作区控制器测试：改动投影与撤销结果的 DTO 映射、错误状态码。
 */
class SessionWorkspaceControllerTest {

    private static final SessionId SESSION_ID = SessionId.parse("01TST000000000000000000001");

    private final ObjectMapper json = new ObjectMapper();
    private GitCheckpointService checkpoints;
    private GitUndoService undo;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        checkpoints = mock(GitCheckpointService.class);
        undo = mock(GitUndoService.class);
        mvc = MockMvcBuilders.standaloneSetup(new SessionWorkspaceController(checkpoints, undo))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void returnsChangesProjection() throws Exception {
        when(checkpoints.changes(SESSION_ID)).thenReturn(changes());

        MvcResult result = mvc.perform(get("/api/v1/sessions/{id}/changes", SESSION_ID))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = bodyOf(result);
        assertThat(body.path("available").asBoolean()).isTrue();
        assertThat(body.path("canUndo").asBoolean()).isTrue();
        assertThat(body.path("branch").asText()).isEqualTo("main");
        assertThat(body.path("headSha").asText()).isEqualTo("head-0");
        assertThat(body.path("fileCount").asInt()).isEqualTo(2);
        assertThat(body.path("additions").asInt()).isEqualTo(5);
        assertThat(body.path("deletions").asInt()).isEqualTo(1);
        assertThat(body.path("suggestedCommit").asText()).isEqualTo("feat: 更新 a.txt");
        JsonNode turn = body.path("turns").get(0);
        assertThat(turn.path("turn").asInt()).isEqualTo(1);
        assertThat(turn.path("diff").asText()).contains("+three");
        JsonNode file = turn.path("files").get(0);
        assertThat(file.path("path").asText()).isEqualTo("a.txt");
        assertThat(file.path("kind").asText()).isEqualTo("MODIFIED");
        assertThat(file.path("binary").asBoolean()).isFalse();
    }

    @Test
    void returnsEmptyProjectionWhenGitIsUnavailable() throws Exception {
        when(checkpoints.changes(SESSION_ID)).thenReturn(SessionGitChanges.EMPTY);

        MvcResult result = mvc.perform(get("/api/v1/sessions/{id}/changes", SESSION_ID))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = bodyOf(result);
        assertThat(body.path("available").asBoolean()).isFalse();
        assertThat(body.path("canUndo").asBoolean()).isFalse();
        assertThat(body.path("turns").size()).isZero();
    }

    @Test
    void returnsUndoResult() throws Exception {
        when(undo.undo(SESSION_ID)).thenReturn(new GitUndoResult(
                List.of("a.txt", "b.txt"), List.of(), true));

        MvcResult result = mvc.perform(post("/api/v1/sessions/{id}/undo", SESSION_ID))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = bodyOf(result);
        assertThat(body.path("rewound").asBoolean()).isTrue();
        assertThat(body.path("restored").size()).isEqualTo(2);
        assertThat(body.path("restored").get(0).asText()).isEqualTo("a.txt");
        assertThat(body.path("restored").get(1).asText()).isEqualTo("b.txt");
        assertThat(body.path("conflicts").size()).isZero();
    }

    @Test
    void returnsConflictsWithoutRewinding() throws Exception {
        when(undo.undo(SESSION_ID)).thenReturn(new GitUndoResult(List.of(),
                List.of(new GitConflict("a.txt", "本轮之后工作区又发生了变化，为避免覆盖已跳过")),
                false));

        MvcResult result = mvc.perform(post("/api/v1/sessions/{id}/undo", SESSION_ID))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = bodyOf(result);
        assertThat(body.path("rewound").asBoolean()).isFalse();
        assertThat(body.path("conflicts").get(0).path("path").asText()).isEqualTo("a.txt");
        assertThat(body.path("conflicts").get(0).path("reason").asText()).contains("变化");
    }

    @Test
    void mapsUnavailableGitToConflict() throws Exception {
        when(undo.undo(SESSION_ID)).thenThrow(new DomainException(
                DomainErrorCode.GIT_UNAVAILABLE, "最近一次运行没有可用的工作区检查点"));

        MvcResult result = mvc.perform(post("/api/v1/sessions/{id}/undo", SESSION_ID))
                .andExpect(status().isConflict())
                .andReturn();

        JsonNode body = bodyOf(result);
        assertThat(body.path("code").asText()).isEqualTo("GIT_UNAVAILABLE");
    }

    private JsonNode bodyOf(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static SessionGitChanges changes() {
        GitCheckpointPayload checkpoint = new GitCheckpointPayload("run-1", null, "tree-0",
                "head-0", "main");
        GitChangeSetPayload turn = new GitChangeSetPayload("run-1", 1, null, "tree-0", "tree-1",
                List.of(new FileChange("a.txt", GitChangeKind.MODIFIED, 3, 1, false),
                        new FileChange("logo.bin", GitChangeKind.ADDED, 2, 0, true)),
                "diff --git a/a.txt b/a.txt\n+three\n", false, "feat: 更新 a.txt");
        return new SessionGitChanges(checkpoint, List.of(turn), true);
    }
}
