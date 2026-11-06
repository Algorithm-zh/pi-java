package com.piagent.trigger.http.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.piagent.application.timeline.SessionRewindResult;
import com.piagent.application.timeline.SessionRewindService;
import com.piagent.application.timeline.SessionTimeline;
import com.piagent.application.timeline.SessionTimelineService;
import com.piagent.domain.git.model.FileChange;
import com.piagent.domain.git.model.GitChangeKind;
import com.piagent.domain.git.model.GitConflict;
import com.piagent.domain.plan.model.PlanItem;
import com.piagent.domain.plan.model.PlanItemStatus;
import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.payload.ApprovalPayload;
import com.piagent.domain.session.model.payload.GitChangeSetPayload;
import com.piagent.domain.session.model.payload.VerificationPayload;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.trigger.http.ApiExceptionHandler;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 时间线与回退控制器测试：DTO 映射、预览/冲突响应与状态码。
 */
class SessionTimelineControllerTest {

    private static final SessionId SESSION_ID = SessionId.parse("01TST000000000000000000001");
    private static final EntryId CHECKPOINT = EntryId.parse("01TST000000000000000000002");

    private final ObjectMapper json = new ObjectMapper();
    private SessionTimelineService timelines;
    private SessionRewindService rewinds;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        timelines = mock(SessionTimelineService.class);
        rewinds = mock(SessionRewindService.class);
        mvc = MockMvcBuilders.standaloneSetup(
                        new SessionTimelineController(timelines, rewinds))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void returnsTimelineNodes() throws Exception {
        when(timelines.timeline(SESSION_ID)).thenReturn(timeline());

        MvcResult result = mvc.perform(get("/api/v1/sessions/{id}/timeline", SESSION_ID))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = bodyOf(result);
        assertThat(body.path("available").asBoolean()).isTrue();
        assertThat(body.path("currentCheckpointEntryId").asText())
                .isEqualTo(CHECKPOINT.toString());
        JsonNode node = body.path("nodes").get(0);
        assertThat(node.path("type").asText()).isEqualTo("RUN");
        assertThat(node.path("checkpointEntryId").asText()).isEqualTo(CHECKPOINT.toString());
        assertThat(node.path("current").asBoolean()).isTrue();
        assertThat(node.path("rewindable").asBoolean()).isTrue();
        assertThat(node.path("intent").asText()).isEqualTo("修复登录");
        assertThat(node.path("plan").path("done").asInt()).isEqualTo(1);
        assertThat(node.path("approvals").path("denied").asInt()).isEqualTo(1);
        assertThat(node.path("verification").path("failed").asInt()).isEqualTo(1);
        assertThat(node.path("changes").path("files").asInt()).isEqualTo(1);
        assertThat(node.path("evidence").path("plan").get(0).path("content").asText())
                .isEqualTo("补测试");
        assertThat(node.path("evidence").path("approvals").get(0).path("decision").asText())
                .isEqualTo("DENIED");
    }

    @Test
    void hidesForeignSessionAsNotFound() throws Exception {
        when(timelines.timeline(SESSION_ID)).thenThrow(
                new DomainException(DomainErrorCode.SESSION_NOT_FOUND, "会话不存在"));

        MvcResult result = mvc.perform(get("/api/v1/sessions/{id}/timeline", SESSION_ID))
                .andExpect(status().isNotFound())
                .andReturn();

        assertThat(bodyOf(result).path("code").asText()).isEqualTo("SESSION_NOT_FOUND");
    }

    @Test
    void previewsRewind() throws Exception {
        when(rewinds.rewind(SESSION_ID, CHECKPOINT, true, "换一种修法"))
                .thenReturn(new SessionRewindResult(true, false, true, List.of("a.txt"),
                        List.of(), List.of(), null, 3, 1, "从 14:32 的决策点回退"));

        MvcResult result = mvc.perform(post("/api/v1/sessions/{id}/rewind", SESSION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "checkpointEntryId", CHECKPOINT.toString(),
                                "dryRun", true,
                                "note", "换一种修法"))))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = bodyOf(result);
        assertThat(body.path("dryRun").asBoolean()).isTrue();
        assertThat(body.path("rewound").asBoolean()).isFalse();
        assertThat(body.path("restorable").asBoolean()).isTrue();
        assertThat(body.path("files").get(0).asText()).isEqualTo("a.txt");
        assertThat(body.path("discardedEntries").asInt()).isEqualTo(3);
        assertThat(body.path("discardedRuns").asInt()).isEqualTo(1);
        assertThat(body.path("brief").asText()).contains("回退");
        verify(rewinds).rewind(SESSION_ID, CHECKPOINT, true, "换一种修法");
    }

    @Test
    void returnsConflictsWithoutCounts() throws Exception {
        when(rewinds.rewind(SESSION_ID, CHECKPOINT, false, null))
                .thenReturn(new SessionRewindResult(false, false, false, List.of("a.txt"),
                        List.of(),
                        List.of(new GitConflict("a.txt",
                                "目标之后工作区又发生了变化，为避免覆盖已跳过")),
                        null, 0, 0, ""));

        MvcResult result = mvc.perform(post("/api/v1/sessions/{id}/rewind", SESSION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "checkpointEntryId", CHECKPOINT.toString(),
                                "dryRun", false))))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = bodyOf(result);
        assertThat(body.path("rewound").asBoolean()).isFalse();
        assertThat(body.path("restorable").asBoolean()).isFalse();
        assertThat(body.path("discardedEntries").asInt()).isZero();
        assertThat(body.path("conflicts").get(0).path("path").asText()).isEqualTo("a.txt");
    }

    @Test
    void defaultsToPreviewWhenDryRunOmitted() throws Exception {
        when(rewinds.rewind(SESSION_ID, CHECKPOINT, true, null))
                .thenReturn(new SessionRewindResult(true, false, true, List.of("a.txt"),
                        List.of(), List.of(), null, 3, 1, "从 14:32 的决策点回退"));

        MvcResult result = mvc.perform(post("/api/v1/sessions/{id}/rewind", SESSION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"checkpointEntryId\":\"" + CHECKPOINT + "\"}"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(bodyOf(result).path("dryRun").asBoolean()).isTrue();
        verify(rewinds).rewind(SESSION_ID, CHECKPOINT, true, null);
    }

    @Test
    void rendersRewindNode() throws Exception {
        EntryId briefEntry = EntryId.parse("01TST000000000000000000042");
        SessionTimeline.RewindEvidence rewind = new SessionTimeline.RewindEvidence(
                briefEntry, "从 14:32 的决策点回退", 3, 1);
        SessionTimeline.Node node = new SessionTimeline.Node(SessionTimeline.NodeKind.REWIND,
                null, "run-1", Instant.parse("2026-11-05T10:05:00Z"), true, false, null, rewind);
        when(timelines.timeline(SESSION_ID))
                .thenReturn(new SessionTimeline(true, null, List.of(node)));

        MvcResult result = mvc.perform(get("/api/v1/sessions/{id}/timeline", SESSION_ID))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = bodyOf(result);
        assertThat(body.path("currentCheckpointEntryId").isNull()).isTrue();
        JsonNode rewindNode = body.path("nodes").get(0);
        assertThat(rewindNode.path("type").asText()).isEqualTo("REWIND");
        assertThat(rewindNode.path("checkpointEntryId").isNull()).isTrue();
        assertThat(rewindNode.path("rewindEntryId").asText()).isEqualTo(briefEntry.toString());
        assertThat(rewindNode.path("brief").asText()).isEqualTo("从 14:32 的决策点回退");
        assertThat(rewindNode.path("discardedEntries").asInt()).isEqualTo(3);
        assertThat(rewindNode.path("discardedRuns").asInt()).isEqualTo(1);
        assertThat(rewindNode.path("intent").asText()).isEmpty();
        assertThat(rewindNode.path("evidence").isNull()).isTrue();
    }

    @Test
    void mapsInvalidBranchToConflict() throws Exception {
        when(rewinds.rewind(SESSION_ID, CHECKPOINT, true, null))
                .thenThrow(new DomainException(DomainErrorCode.INVALID_BRANCH,
                        "目标不是当前分支上的检查点"));

        MvcResult result = mvc.perform(post("/api/v1/sessions/{id}/rewind", SESSION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "checkpointEntryId", CHECKPOINT.toString(),
                                "dryRun", true))))
                .andExpect(status().isConflict())
                .andReturn();

        assertThat(bodyOf(result).path("code").asText()).isEqualTo("INVALID_BRANCH");
    }

    @Test
    void rejectsBlankCheckpointId() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/sessions/{id}/rewind", SESSION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dryRun\":true}"))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertThat(bodyOf(result).path("code").asText()).isEqualTo("INVALID_ARGUMENT");
    }

    private JsonNode bodyOf(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static SessionTimeline timeline() {
        PlanItem item = new PlanItem("补测试", PlanItemStatus.PENDING);
        ApprovalPayload approval = new ApprovalPayload("ap-1", "call-1", "write",
                "DENIED", "不要改这里", "user");
        VerificationPayload verification = new VerificationPayload("run-1", "mvn test", false,
                1, false, false, 1200, "FAIL");
        GitChangeSetPayload change = new GitChangeSetPayload("run-1", 1, null, "tree-0",
                "tree-1",
                List.of(new FileChange("a.txt", GitChangeKind.MODIFIED, 2, 1, false)),
                "diff", false, "fix: 更新 a.txt");
        SessionTimeline.Stats stats = new SessionTimeline.Stats(
                new SessionTimeline.PlanSummary(2, 1),
                new SessionTimeline.ApprovalSummary(1, 1),
                new SessionTimeline.VerificationSummary(1, 1),
                new SessionTimeline.ChangeSummary(1, 1, 2, 1));
        SessionTimeline.RunEvidence run = new SessionTimeline.RunEvidence("修复登录", stats,
                "COMPLETED", 3, 5, List.of(item), List.of(approval),
                List.of(verification), List.of(change));
        SessionTimeline.Node node = new SessionTimeline.Node(SessionTimeline.NodeKind.RUN,
                CHECKPOINT, "run-1", Instant.parse("2026-11-05T10:00:00Z"), true, true,
                run, null);
        return new SessionTimeline(true, CHECKPOINT, List.of(node));
    }
}
