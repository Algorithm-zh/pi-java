package com.piagent.app.timeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.piagent.app.PiJavaApplication;
import com.piagent.application.agent.command.SendMessageCommand;
import com.piagent.application.agent.readmodel.RunHandle;
import com.piagent.application.agent.service.AgentLoopService;
import com.piagent.application.session.command.CreateSessionCommand;
import com.piagent.application.session.service.SessionApplicationService;
import com.piagent.application.timeline.SessionBranchComparison;
import com.piagent.application.timeline.SessionBranchComparisonService;
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
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.ThinkingLevel;
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
 * 分叉对比的应用级集成测试：两轮写入 → 回退 → 两条新分支继续写。
 */
@SpringBootTest(classes = PiJavaApplication.class,
        properties = {"pi.fake.demo-mode=true", "pi.git.enabled=true"})
@ActiveProfiles("test")
class BranchComparisonIntegrationTest {

    private static final String MODEL_ID = "comparison-test-fake";
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
    private SessionBranchComparisonService comparisons;

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
        workspace = Path.of("target/comparison-integration-workspace").toAbsolutePath().normalize();
        deleteRecursively(workspace);
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("notes.md"), "base\n");
        git("init", "-q");
        git("add", "notes.md");
        git("-c", "user.name=pi", "-c", "user.email=pi@localhost", "commit", "-qm", "init");
        models.insert(ModelProfile.create(new ModelId(MODEL_ID), "Comparison Fake",
                ModelEndpoint.fake(MODEL_ID)));
        policies.insert(ToolPolicyRule.create(
                ToolPolicyKey.workspace(WorkspaceKey.of(workspace), new ToolName("write")),
                ToolPolicySettings.allow(ToolRisk.HIGH)));
    }

    @Test
    void comparesAbandonedAndCurrentBranchesAfterRewind() {
        ChatSession session = sessions.createSession(
                new CreateSessionCommand("分叉对比", workspace, MODEL_ID, ThinkingLevel.OFF));
        SessionId sessionId = session.identity().id();
        run(sessionId, "/write old.txt 旧分支");
        run(sessionId, "/write shared.txt 旧共享");

        SessionTimeline timeline = timelines.timeline(sessionId);
        assertThat(timeline.nodes()).hasSize(2);
        SessionTimeline.Node target = timeline.nodes().get(1);
        SessionRewindResult rewind = rewinds.rewind(sessionId, target.checkpointEntryId(),
                false, "换一种写法");
        assertThat(rewind.rewound()).isTrue();
        assertThat(Files.exists(workspace.resolve("shared.txt"))).isFalse();

        run(sessionId, "/write shared.txt 新共享");
        run(sessionId, "/write new.txt 新分支");

        SessionBranchComparison comparison = comparisons.compare(sessionId,
                rewind.briefEntryId());

        assertThat(comparison.available()).isTrue();
        assertThat(comparison.rewindEntryId()).isEqualTo(rewind.briefEntryId());
        assertThat(comparison.forkEntryId()).isNotNull();
        assertThat(comparison.abandoned().intents())
                .containsExactly("/write shared.txt 旧共享");
        assertThat(comparison.abandoned().runs()).isEqualTo(1);
        assertThat(comparison.abandoned().changes().files()).isEqualTo(1);
        assertThat(comparison.current().intents())
                .containsExactly("/write shared.txt 新共享", "/write new.txt 新分支");
        assertThat(comparison.current().runs()).isEqualTo(2);
        assertThat(comparison.current().changes().files()).isEqualTo(2);
        assertThat(comparison.files().onlyAbandoned()).isEmpty();
        assertThat(comparison.files().onlyCurrent()).containsExactly("new.txt");
        assertThat(comparison.files().both()).containsExactly("shared.txt");
        assertThat(comparison.summary()).contains("旧分支").contains("当前分支");
        assertThat(comparison.abandoned().files())
                .extracting(SessionBranchComparison.FileSummary::path)
                .containsExactly("shared.txt");
    }

    private void run(SessionId sessionId, String text) {
        RunHandle handle = agentLoop.start(new SendMessageCommand(sessionId, text));
        AgentRun run = agentLoop.awaitTerminal(handle.runId(), TIMEOUT);
        assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
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