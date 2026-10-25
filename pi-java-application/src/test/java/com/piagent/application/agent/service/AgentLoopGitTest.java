package com.piagent.application.agent.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.piagent.application.testsupport.ScriptedLlmGateway;
import com.piagent.domain.agent.AgentRun;
import com.piagent.domain.agent.RunEvent;
import com.piagent.domain.agent.RunEventType;
import com.piagent.domain.agent.RunStatus;
import com.piagent.domain.git.model.FileChange;
import com.piagent.domain.git.model.GitChangeKind;
import com.piagent.domain.git.model.GitChangeSet;
import com.piagent.domain.git.model.GitRestoreResult;
import com.piagent.domain.git.model.GitSnapshot;
import com.piagent.domain.git.port.WorkspaceGit;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.EntryType;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.message.ToolCallBlock;
import com.piagent.domain.session.model.payload.GitChangeSetPayload;
import com.piagent.domain.session.model.payload.GitCheckpointPayload;
import com.piagent.domain.tool.model.ExecutionMode;
import com.piagent.domain.tool.model.ToolRisk;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Agent Loop 与 Git 检查点的集成测试。
 *
 * <p>验证 run 开始时写检查点、工具执行后写逐轮改动并发出
 * {@code workspace.changed}，以及 Git 故障时 run 仍能正常结束。</p>
 */
class AgentLoopGitTest {

    @Test
    void tracksTurnChangesAndPublishesEvent() {
        ScriptedGit git = new ScriptedGit();
        git.snapshots.add(new GitSnapshot("tree-0", "head-0", "main"));
        git.snapshots.add(new GitSnapshot("tree-1", "head-0", "main"));
        git.changes = new GitChangeSet("tree-0", "tree-1",
                List.of(new FileChange("notes.md", GitChangeKind.ADDED, 2, 0, false)),
                "diff --git a/notes.md b/notes.md\n+hello\n", false);
        AgentLoopFixture fixture = new AgentLoopFixture(git);
        fixture.tools.add("read", ToolRisk.LOW, ExecutionMode.SEQUENTIAL);
        ChatSession session = fixture.createSession();
        fixture.gateway.enqueue(ScriptedLlmGateway.toolCalls(List.of(call("call-1", "read"))));
        fixture.gateway.enqueue(ScriptedLlmGateway.text("完成"));

        AgentRun run = fixture.run(session.identity().id(), "读文件");

        assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
        List<SessionEntry> branch = fixture.branch(session.identity().id());
        assertThat(branch).extracting(SessionEntry::type).containsSubsequence(
                EntryType.GIT_CHECKPOINT, EntryType.GIT_CHANGES);
        GitCheckpointPayload checkpoint = (GitCheckpointPayload) entry(branch,
                EntryType.GIT_CHECKPOINT).payload();
        assertThat(checkpoint.baseTreeSha()).isEqualTo("tree-0");
        assertThat(checkpoint.branch()).isEqualTo("main");
        GitChangeSetPayload change = (GitChangeSetPayload) entry(branch,
                EntryType.GIT_CHANGES).payload();
        assertThat(change.turn()).isEqualTo(1);
        assertThat(change.treeSha()).isEqualTo("tree-1");
        assertThat(change.suggestedCommit()).isNotBlank();

        List<RunEvent> events = fixture.events(run.id());
        assertThat(events).extracting(RunEvent::type).contains(RunEventType.WORKSPACE_CHANGED);
        Map<String, Object> data = events.stream()
                .filter(event -> event.type() == RunEventType.WORKSPACE_CHANGED)
                .findFirst().orElseThrow().data();
        assertThat(data.get("turn")).isEqualTo(1);
        assertThat(data.get("files")).isEqualTo(List.of("notes.md"));
        assertThat(data.get("additions")).isEqualTo(2);
        assertThat(data.get("deletions")).isEqualTo(0);
    }

    @Test
    void completesRunWhenGitFails() {
        AgentLoopFixture fixture = new AgentLoopFixture(new BrokenGit());
        fixture.tools.add("read", ToolRisk.LOW, ExecutionMode.SEQUENTIAL);
        ChatSession session = fixture.createSession();
        fixture.gateway.enqueue(ScriptedLlmGateway.toolCalls(List.of(call("call-1", "read"))));
        fixture.gateway.enqueue(ScriptedLlmGateway.text("完成"));

        AgentRun run = fixture.run(session.identity().id(), "读文件");

        assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(fixture.branch(session.identity().id())).extracting(SessionEntry::type)
                .doesNotContain(EntryType.GIT_CHECKPOINT, EntryType.GIT_CHANGES);
        assertThat(fixture.events(run.id())).extracting(RunEvent::type)
                .doesNotContain(RunEventType.WORKSPACE_CHANGED);
    }

    private static SessionEntry entry(List<SessionEntry> branch, EntryType type) {
        return branch.stream().filter(entry -> entry.type() == type).findFirst().orElseThrow();
    }

    private static ToolCallBlock call(String callId, String toolName) {
        return new ToolCallBlock(callId, toolName, Map.of("path", "README.md"));
    }

    /**
     * 预设快照与改动的 Git 替身。
     */
    private static final class ScriptedGit implements WorkspaceGit {

        private final Deque<GitSnapshot> snapshots = new ArrayDeque<>();
        private GitChangeSet changes;

        @Override
        public Optional<GitSnapshot> snapshot(Path workspace) {
            return Optional.ofNullable(snapshots.pollFirst());
        }

        @Override
        public GitChangeSet changes(Path workspace, String baseTreeSha, String treeSha) {
            return changes;
        }

        @Override
        public GitRestoreResult restore(Path workspace, String baseTreeSha, String treeSha) {
            throw new UnsupportedOperationException("本测试不验证撤销");
        }
    }

    /**
     * 所有操作都失败的 Git 替身，用于验证降级。
     */
    private static final class BrokenGit implements WorkspaceGit {

        @Override
        public Optional<GitSnapshot> snapshot(Path workspace) {
            throw new IllegalStateException("git 不可用");
        }

        @Override
        public GitChangeSet changes(Path workspace, String baseTreeSha, String treeSha) {
            throw new IllegalStateException("git 不可用");
        }

        @Override
        public GitRestoreResult restore(Path workspace, String baseTreeSha, String treeSha) {
            throw new IllegalStateException("git 不可用");
        }
    }
}
