package com.piagent.trigger.cli;

import com.piagent.application.git.GitCheckpointService;
import com.piagent.application.git.GitUndoResult;
import com.piagent.application.git.GitUndoService;
import com.piagent.application.git.SessionGitChanges;
import com.piagent.domain.git.model.FileChange;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.payload.GitChangeSetPayload;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * chat 内置的 Git 相关命令。
 *
 * <p>{@code /diff} 只读最近一次运行；{@code /undo} 复用撤销用例，撤销后重新加载会话。</p>
 */
@Component
public class CliGitSupport {

    private static final int MAX_DIFF_LINES = 120;

    private final CliSessionService sessions;
    private final GitCheckpointService checkpoints;
    private final GitUndoService undo;

    /**
     * 创建 Git 命令支持。
     *
     * @param sessions 会话加载
     * @param checkpoints 工作区改动查询
     * @param undo 撤销用例
     */
    public CliGitSupport(CliSessionService sessions, GitCheckpointService checkpoints,
            GitUndoService undo) {
        this.sessions = sessions;
        this.checkpoints = checkpoints;
        this.undo = undo;
    }

    /**
     * 打印最近一次运行的文件改动与 diff。
     *
     * @param session 当前会话
     * @param terminal 输出终端
     */
    public void diff(ChatSession session, CliTerminal terminal) {
        SessionGitChanges changes = checkpoints.changes(session.identity().id());
        if (!changes.available()) {
            terminal.println("当前目录不是 Git 仓库，或 Git 检查点已关闭");
            return;
        }
        if (changes.turns().isEmpty()) {
            terminal.println("最近一次运行没有文件改动");
            return;
        }
        String location = changes.checkpoint().branch() == null
                ? "游离 HEAD" : changes.checkpoint().branch();
        terminal.println("最近一次运行 · " + location + " · " + changes.fileCount()
                + " 个文件 +" + changes.additions() + " -" + changes.deletions());
        for (GitChangeSetPayload turn : changes.turns()) {
            terminal.println("第 " + turn.turn() + " 轮");
            for (FileChange file : turn.files()) {
                terminal.println("  " + mark(file.kind().name()) + " " + file.path()
                        + " +" + file.additions() + " -" + file.deletions()
                        + (file.binary() ? " · 二进制" : ""));
            }
            if (!turn.suggestedCommit().isBlank()) {
                terminal.println("  建议提交: " + turn.suggestedCommit());
            }
        }
        printDiffBody(changes, terminal);
    }

    /**
     * 撤销上一轮，还原文件并把对话退回上一轮之前。
     *
     * @param session 当前会话
     * @param terminal 输出终端
     * @return 撤销后的会话；未执行时返回原会话
     */
    public ChatSession undo(ChatSession session, CliTerminal terminal) {
        GitUndoResult result = undo.undo(session.identity().id());
        if (!result.rewound()) {
            terminal.println("撤销未执行：以下文件在本轮之后又被修改");
            result.conflicts().forEach(conflict ->
                    terminal.println("  ! " + conflict.path() + " · " + conflict.reason()));
            return session;
        }
        terminal.println("已撤销上一轮，还原 " + result.restored().size() + " 个文件");
        result.restored().forEach(path -> terminal.println("  - " + path));
        return sessions.require(session.identity().id().toString());
    }

    private static void printDiffBody(SessionGitChanges changes, CliTerminal terminal) {
        String diff = changes.turns().stream()
                .map(GitChangeSetPayload::diff)
                .filter(text -> !text.isBlank())
                .collect(Collectors.joining("\n"));
        if (diff.isBlank()) {
            return;
        }
        String[] lines = diff.split("\n", -1);
        int shown = Math.min(lines.length, MAX_DIFF_LINES);
        terminal.println("");
        for (int index = 0; index < shown; index++) {
            terminal.println(lines[index]);
        }
        if (lines.length > shown) {
            terminal.println("…（还有 " + (lines.length - shown) + " 行，完整 diff 见 Web 改动面板）");
        }
    }

    private static String mark(String kind) {
        return switch (kind) {
            case "ADDED" -> "+";
            case "DELETED" -> "-";
            default -> "~";
        };
    }
}