package com.piagent.trigger.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.piagent.application.context.CompactionApplicationService;
import com.piagent.application.git.GitCheckpointService;
import com.piagent.application.git.GitUndoResult;
import com.piagent.application.git.GitUndoService;
import com.piagent.application.git.SessionGitChanges;
import com.piagent.application.session.service.SessionSettingsApplicationService;
import com.piagent.domain.git.model.FileChange;
import com.piagent.domain.git.model.GitChangeKind;
import com.piagent.domain.git.model.GitConflict;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.SessionConfig;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.payload.GitChangeSetPayload;
import com.piagent.domain.session.model.payload.GitCheckpointPayload;
import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * chat 内置的 {@code /diff} 与 {@code /undo} 输出测试。
 */
class CliChatSupportGitTest {

    private static final SessionId SESSION_ID = SessionId.parse("01TST000000000000000000001");

    private final CliSessionService sessions = mock(CliSessionService.class);
    private final SessionSettingsApplicationService settings =
            mock(SessionSettingsApplicationService.class);
    private final CompactionApplicationService compaction = mock(CompactionApplicationService.class);
    private final GitCheckpointService checkpoints = mock(GitCheckpointService.class);
    private final GitUndoService undo = mock(GitUndoService.class);
    private final CliChatSupport support =
            new CliChatSupport(sessions, settings, compaction, checkpoints, undo);
    private final ChatSession session = ChatSession.createRoot(SESSION_ID, "Git 命令测试",
            SessionConfig.forWorkspace(Path.of("D:/demo"), "fake-demo"));
    private final StringWriter out = new StringWriter();
    private final CliTerminal terminal = new CliTerminal(
            new BufferedReader(new StringReader("")), new PrintWriter(out, true),
            new PrintWriter(new StringWriter(), true));

    @BeforeEach
    void setUp() {
        when(checkpoints.changes(SESSION_ID)).thenReturn(changes());
    }

    @Test
    void printsLatestRunChangesAndDiff() {
        support.handle(session, "/diff", terminal);

        assertThat(out.toString())
                .contains("最近一次运行 · main · 2 个文件 +4 -1")
                .contains("第 1 轮")
                .contains("~ a.txt +3 -1")
                .contains("+ logo.bin +1 -0 · 二进制")
                .contains("建议提交: feat: 更新 a.txt")
                .contains("+hello");
    }

    @Test
    void reportsUnavailableAndEmptyRuns() {
        when(checkpoints.changes(SESSION_ID)).thenReturn(SessionGitChanges.EMPTY);
        support.handle(session, "/diff", terminal);
        assertThat(out.toString()).contains("当前目录不是 Git 仓库");

        when(checkpoints.changes(SESSION_ID)).thenReturn(
                new SessionGitChanges(checkpoint(), List.of(), true));
        out.getBuffer().setLength(0);
        support.handle(session, "/diff", terminal);
        assertThat(out.toString()).contains("最近一次运行没有文件改动");
    }

    @Test
    void undoPrintsRestoredFiles() {
        when(undo.undo(SESSION_ID)).thenReturn(new GitUndoResult(List.of("a.txt"), List.of(), true));
        when(sessions.require(SESSION_ID.toString())).thenReturn(session);

        ChatSession result = support.handle(session, "/undo", terminal);

        assertThat(result).isSameAs(session);
        assertThat(out.toString()).contains("已撤销上一轮，还原 1 个文件").contains("  - a.txt");
    }

    @Test
    void undoPrintsConflicts() {
        when(undo.undo(SESSION_ID)).thenReturn(new GitUndoResult(List.of(),
                List.of(new GitConflict("a.txt", "本轮之后工作区又发生了变化，为避免覆盖已跳过")),
                false));

        ChatSession result = support.handle(session, "/undo", terminal);

        assertThat(result).isSameAs(session);
        assertThat(out.toString()).contains("撤销未执行").contains("! a.txt");
    }

    private static SessionGitChanges changes() {
        GitChangeSetPayload turn = new GitChangeSetPayload("run-1", 1, null, "tree-0", "tree-1",
                List.of(new FileChange("a.txt", GitChangeKind.MODIFIED, 3, 1, false),
                        new FileChange("logo.bin", GitChangeKind.ADDED, 1, 0, true)),
                "diff --git a/a.txt b/a.txt\n+hello\n", false, "feat: 更新 a.txt");
        return new SessionGitChanges(checkpoint(), List.of(turn), true);
    }

    private static GitCheckpointPayload checkpoint() {
        return new GitCheckpointPayload("run-1", null, "tree-0", "head-0", "main");
    }
}
