package com.piagent.app.timeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.piagent.app.PiJavaApplication;
import com.piagent.application.agent.command.SendMessageCommand;
import com.piagent.application.agent.readmodel.RunHandle;
import com.piagent.application.agent.service.AgentLoopService;
import com.piagent.application.session.command.CreateSessionCommand;
import com.piagent.application.session.service.SessionApplicationService;
import com.piagent.application.timeline.SessionRewindResult;
import com.piagent.application.timeline.SessionRewindService;
import com.piagent.application.timeline.SessionTimeline;
import com.piagent.application.timeline.SessionTimelineService;
import com.piagent.domain.agent.AgentRun;
import com.piagent.domain.agent.RunStatus;
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
import com.piagent.domain.session.model.payload.GitCheckpointPayload;
import com.piagent.domain.session.port.SessionEntryStore;
import com.piagent.domain.session.port.SessionRepository;
import com.piagent.domain.session.service.SessionContextProjector;
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
 * 时间线与回退的应用级集成测试：两次 run → 时间线 → 回退 → 继续运行。
 */
@SpringBootTest(classes = PiJavaApplication.class,
        properties = {"pi.fake.demo-mode=true", "pi.git.enabled=true"})
@ActiveProfiles("test")
class DecisionTimelineIntegrationTest {

    private static final String MODEL_ID = "timeline-test-fake";
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
    private SessionRepository sessionRepository;

    @Autowired
    private SessionEntryStore entryStore;

    @Autowired
    private ModelProfileRepository models;

    @Autowired
    private JdbcTemplate jdbc;

    private Path workspace;
    private String notes;

    @BeforeEach
    void setUp() throws IOException {
        jdbc.update("DELETE FROM pi_session_entry");
        jdbc.update("DELETE FROM pi_session");
        jdbc.update("DELETE FROM pi_tool_policy");
        jdbc.update("DELETE FROM pi_model_profile");
        workspace = Path.of("target/timeline-integration-workspace").toAbsolutePath().normalize();
        deleteRecursively(workspace);
        Files.createDirectories(workspace);
        notes = "one\n" + System.nanoTime() + "\n";
        Files.writeString(workspace.resolve("notes.md"), notes);
        git("init", "-q");
        git("add", "notes.md");
        git("-c", "user.name=pi", "-c", "user.email=pi@localhost", "commit", "-qm", "init");
        models.insert(ModelProfile.create(new ModelId(MODEL_ID), "Timeline Fake",
                ModelEndpoint.fake(MODEL_ID)));
    }

    @Test
    void rewindsSecondRunAndContinuesWithBriefInContext() throws IOException {
        ChatSession session = sessions.createSession(
                new CreateSessionCommand("时间线集成", workspace, MODEL_ID, ThinkingLevel.OFF));
        SessionId sessionId = session.identity().id();
        run(sessionId, "第一次");
        run(sessionId, "第二次");

        SessionTimeline timeline = timelines.timeline(sessionId);
        assertThat(timeline.available()).isTrue();
        assertThat(timeline.nodes()).hasSize(2);
        SessionTimeline.Node target = timeline.nodes().get(1);
        assertThat(target.current()).isTrue();
        assertThat(target.checkpointEntryId()).isNotNull();

        SessionRewindResult result = rewinds.rewind(sessionId, target.checkpointEntryId(),
                false, "换一种修法");
        assertThat(result.rewound()).isTrue();
        assertThat(result.briefEntryId()).isNotNull();

        List<SessionEntry> branch = currentBranch(sessionId);
        SessionEntry brief = branch.stream()
                .filter(entry -> entry.id().equals(result.briefEntryId()))
                .findFirst().orElseThrow();
        assertThat(brief.payload()).isInstanceOfSatisfying(BranchSummaryPayload.class,
                payload -> {
                    assertThat(payload.details().get("kind")).isEqualTo("rewind");
                    assertThat(payload.summary()).contains("换一种修法");
                });

        // 假模型只回文本、不调用写工具：还原计划与还原结果都应为空。
        assertThat(result.files()).isEmpty();
        assertThat(result.restored()).isEmpty();
        assertThat(result.discardedRuns()).isEqualTo(1);

        // 第二次 run 的检查点已被丢弃，当前分支只剩第一次 run 的检查点与这条简报。
        assertThat(branch.stream()
                .filter(entry -> entry.payload() instanceof GitCheckpointPayload)
                .map(entry -> ((GitCheckpointPayload) entry.payload()).runId()))
                .containsExactly(timeline.nodes().get(0).runId());
        assertThat(branch.getLast().id()).isEqualTo(result.briefEntryId());
        assertThat(Files.exists(workspace.resolve("notes.md"))).isTrue();
        assertThat(Files.readString(workspace.resolve("notes.md"))).isEqualTo(notes);

        run(sessionId, "按新思路继续");
        List<SessionEntry> continued = currentBranch(sessionId);
        SessionContextProjector projector = new SessionContextProjector();
        assertThat(projector.project(continued, continued.getLast().id()).messages())
                .hasAtLeastOneElementOfType(BranchSummaryMessage.class);
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
