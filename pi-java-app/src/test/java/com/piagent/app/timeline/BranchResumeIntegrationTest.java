package com.piagent.app.timeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.piagent.app.PiJavaApplication;
import com.piagent.application.agent.command.SendMessageCommand;
import com.piagent.application.agent.readmodel.RunHandle;
import com.piagent.application.agent.service.AgentLoopService;
import com.piagent.application.session.command.CreateSessionCommand;
import com.piagent.application.session.service.SessionApplicationService;
import com.piagent.application.timeline.BranchResumeResult;
import com.piagent.application.timeline.SessionBranchComparison;
import com.piagent.application.timeline.SessionBranchComparisonService;
import com.piagent.application.timeline.SessionBranchResumeService;
import com.piagent.application.timeline.SessionRewindResult;
import com.piagent.application.timeline.SessionRewindService;
import com.piagent.application.timeline.SessionTimeline;
import com.piagent.application.timeline.SessionTimelineService;
import com.piagent.domain.agent.AgentRun;
import com.piagent.domain.agent.RunStatus;
import com.piagent.domain.governance.model.ToolPolicyKey;
import com.piagent.domain.governance.model.ToolPolicyRule;
import com.piagent.domain.governance.model.ToolPolicySettings;
import com.piagent.domain.governance.model.WorkspaceKey;
import com.piagent.domain.governance.port.ToolPolicyRepository;
import com.piagent.domain.model.ModelEndpoint;
import com.piagent.domain.model.ModelId;
import com.piagent.domain.model.ModelProfile;
import com.piagent.domain.model.port.ModelProfileRepository;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.ThinkingLevel;
import com.piagent.domain.session.model.message.BranchSummaryMessage;
import com.piagent.domain.session.model.payload.BranchSummaryPayload;
import com.piagent.domain.session.port.SessionEntryStore;
import com.piagent.domain.session.port.SessionRepository;
import com.piagent.domain.session.service.SessionContextProjector;
import com.piagent.domain.tool.model.ToolName;
import com.piagent.domain.tool.model.ToolRisk;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 分支切回的应用级集成测试：回退到分叉点 → 两条分支各自继续 → 来回切换并核对工作区。
 */
@SpringBootTest(classes = PiJavaApplication.class,
        properties = {"pi.fake.demo-mode=true", "pi.git.enabled=true"})
@ActiveProfiles("test")
class BranchResumeIntegrationTest {

    private static final String MODEL_ID = "resume-test-fake";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    @Autowired
    private AgentLoopService agentLoop;

    @Autowired
    private SessionApplicationService sessions;

    @Autowired
    private SessionTimelineService timelines;

    @Autowired
    private SessionRewindService rewinds;

    @Autowired
    private SessionBranchResumeService resumes;

    @Autowired
    private SessionBranchComparisonService comparisons;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private SessionEntryStore entryStore;

    @Autowired
    private ModelProfileRepository models;

    @Autowired
    private ToolPolicyRepository policies;

    @Autowired
    private JdbcTemplate jdbc;

    private Path workspace;

    @BeforeEach
    void setUp() throws IOException {
        jdbc.update("DELETE FROM pi_session_entry");
        jdbc.update("DELETE FROM pi_session");
        jdbc.update("DELETE FROM pi_tool_policy");
        jdbc.update("DELETE FROM pi_model_profile");
        workspace = Path.of("target/resume-integration-workspace").toAbsolutePath().normalize();
        deleteRecursively(workspace);
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("notes.md"), "base\n");
        git("init", "-q");
        git("add", "notes.md");
        git("-c", "user.name=pi", "-c", "user.email=pi@localhost", "commit", "-qm", "init");
        models.insert(ModelProfile.create(new ModelId(MODEL_ID), "Resume Fake",
                ModelEndpoint.fake(MODEL_ID)));
        policies.insert(ToolPolicyRule.create(
                ToolPolicyKey.workspace(WorkspaceKey.of(workspace), new ToolName("write")),
                ToolPolicySettings.allow(ToolRisk.HIGH)));
    }

    @Test
    void switchesBackAndForthWithWorkspaceAndContext() throws IOException {
        ChatSession session = sessions.createSession(
                new CreateSessionCommand("分支切回", workspace, MODEL_ID, ThinkingLevel.OFF));
        SessionId sessionId = session.identity().id();
        run(sessionId, "/write old.txt 旧分支");
        run(sessionId, "/write shared.txt 旧共享");

        SessionTimeline before = timelines.timeline(sessionId);
        SessionRewindResult rewind = rewinds.rewind(sessionId,
                before.nodes().get(1).checkpointEntryId(), false, "换一种写法");
        assertThat(rewind.rewound()).isTrue();
        assertThat(Files.exists(workspace.resolve("shared.txt"))).isFalse();

        run(sessionId, "/write shared.txt 新共享");
        run(sessionId, "/write new.txt 新分支");

        BranchResumeResult preview = resumes.resume(sessionId, rewind.briefEntryId(), true, null);
        assertThat(preview.available()).isTrue();
        assertThat(preview.restorable()).isTrue();
        assertThat(preview.files()).anyMatch(path -> path.endsWith("shared.txt"));

        BranchResumeResult switched = resumes.resume(sessionId, rewind.briefEntryId(), false,
                "先跑测试");
        assertThat(switched.switched()).isTrue();
        assertThat(Files.readString(workspace.resolve("shared.txt"))).isEqualTo("旧共享");
        assertThat(Files.exists(workspace.resolve("new.txt"))).isFalse();
        assertThat(Files.exists(workspace.resolve("old.txt"))).isTrue();

        List<SessionEntry> branch = currentBranch(sessionId);
        assertThat(branch.getLast().id()).isEqualTo(switched.briefEntryId());
        SessionEntry brief = branch.getLast();
        assertThat(brief.payload()).isInstanceOfSatisfying(BranchSummaryPayload.class,
                payload -> {
                    assertThat(payload.details().get("kind")).isEqualTo("resume");
                    assertThat(payload.summary()).contains("先跑测试");
                });
        SessionContextProjector projector = new SessionContextProjector();
        assertThat(projector.project(branch, branch.getLast().id()).messages())
                .hasAtLeastOneElementOfType(BranchSummaryMessage.class);

        SessionTimeline resumed = timelines.timeline(sessionId);
        assertThat(resumed.nodes().getLast().kind())
                .isEqualTo(SessionTimeline.NodeKind.RESUME);

        BranchResumeResult back = resumes.resume(sessionId, switched.briefEntryId(), false, null);
        assertThat(back.switched()).isTrue();
        assertThat(Files.readString(workspace.resolve("shared.txt"))).isEqualTo("新共享");
        assertThat(Files.exists(workspace.resolve("new.txt"))).isTrue();
        // old.txt 写在分叉点之前，两条分支都保留。
        assertThat(Files.exists(workspace.resolve("old.txt"))).isTrue();

        SessionBranchComparison comparison = comparisons.compare(sessionId,
                back.briefEntryId());
        assertThat(comparison.available()).isTrue();
        assertThat(comparison.branchEntryId()).isEqualTo(back.briefEntryId());
    }

    private void run(SessionId sessionId, String text) {
        RunHandle handle = agentLoop.start(new SendMessageCommand(sessionId, text));
        AgentRun run = agentLoop.awaitTerminal(handle.runId(), TIMEOUT);
        assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
    }

    private List<SessionEntry> currentBranch(SessionId sessionId) {
        ChatSession session = sessionRepository.findById(sessionId).orElseThrow();
        return entryStore.loadBranch(sessionId, session.leafEntryId());
    }

    // 中断的运行会留下陈旧的 .git（含只读对象与 index.lock），先清掉再重建。
    private static void deleteRecursively(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                makeWritable(path);
                Files.deleteIfExists(path);
            }
        }
    }

    private static void makeWritable(Path path) {
        try {
            Files.setAttribute(path, "dos:readonly", false);
        } catch (IOException | UnsupportedOperationException error) {
            // 非 Windows 或属性视图不可用时保持原样，delete 会给出真正的原因。
        }
    }

    private void git(String... args) throws IOException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(workspace.toFile())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        try {
            if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IOException("git 失败: " + output);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("git 被中断", error);
        }
    }
}
