package com.piagent.trigger.cli;

import com.piagent.application.context.CompactionApplicationService;
import com.piagent.application.context.ManualCompactionView;
import com.piagent.application.git.GitCheckpointService;
import com.piagent.application.git.GitUndoResult;
import com.piagent.application.git.GitUndoService;
import com.piagent.application.git.SessionGitChanges;
import com.piagent.application.plan.SessionPlanService;
import com.piagent.application.plan.SessionPlanSnapshot;
import com.piagent.application.session.command.ChangeSessionModelCommand;
import com.piagent.application.session.service.SessionSettingsApplicationService;
import com.piagent.domain.git.model.FileChange;
import com.piagent.domain.plan.model.PlanItem;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.payload.GitChangeSetPayload;
import com.piagent.domain.session.model.payload.VerificationPayload;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * chat 的内置斜杠命令，供行模式与 TUI 共用。
 *
 * <p>只负责展示与调用应用层用例；具体界面怎么排版、怎么读输入由调用方决定。</p>
 */
@Component
public class CliChatSupport {

    private static final Set<String> COMMANDS = Set.of(
            "/help", "/exit", "/quit", "/abort", "/compact", "/model", "/tree", "/session",
            "/diff", "/undo", "/plan");

    private static final int MAX_DIFF_LINES = 120;

    private final CliSessionService sessions;
    private final SessionSettingsApplicationService settings;
    private final CompactionApplicationService compaction;
    private final GitCheckpointService checkpoints;
    private final GitUndoService undo;
    private final SessionPlanService plans;

    /**
     * 创建命令支持。
     *
     * @param sessions 会话准备
     * @param settings 会话设置
     * @param compaction 手动压缩
     * @param checkpoints 工作区改动查询
     * @param undo 撤销用例
     * @param plans 计划查询
     */
    public CliChatSupport(CliSessionService sessions, SessionSettingsApplicationService settings,
            CompactionApplicationService compaction, GitCheckpointService checkpoints,
            GitUndoService undo, SessionPlanService plans) {
        this.sessions = sessions;
        this.settings = settings;
        this.compaction = compaction;
        this.checkpoints = checkpoints;
        this.undo = undo;
        this.plans = plans;
    }

    /**
     * 判断输入是否为内置命令；{@code /skill:}、{@code /write} 等按普通消息发送。
     *
     * @param input 已去空白的输入
     * @return true 表示内置命令
     */
    static boolean isCommand(String input) {
        return COMMANDS.contains(input.split("\\s+", 2)[0]);
    }

    /**
     * 执行一条内置命令。
     *
     * @param session 当前会话
     * @param input 已去空白的输入
     * @param terminal 输出终端
     * @return 执行后的会话；切换模型时返回新实例
     */
    public ChatSession handle(ChatSession session, String input, CliTerminal terminal) {
        String[] parts = input.split("\\s+", 2);
        String command = parts[0];
        String argument = parts.length > 1 ? parts[1].strip() : "";
        switch (command) {
            case "/help" -> terminal.println(helpText());
            case "/abort" -> terminal.println("[提示] 当前没有运行中的任务");
            case "/compact" -> compact(session, terminal);
            case "/model" -> {
                return changeModel(session, argument, terminal);
            }
            case "/tree" -> printTree(session, terminal);
            case "/session" -> printSession(session, terminal);
            case "/diff" -> printDiff(session, terminal);
            case "/plan" -> printPlan(session, terminal);
            case "/undo" -> {
                return undo(session, terminal);
            }
            default -> terminal.println("未知命令: " + command + "，输入 /help 查看帮助");
        }
        return session;
    }

    private void printDiff(ChatSession session, CliTerminal terminal) {
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

    private void printDiffBody(SessionGitChanges changes, CliTerminal terminal) {
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

    private void printPlan(ChatSession session, CliTerminal terminal) {
        SessionPlanSnapshot snapshot = plans.snapshot(session.identity().id());
        if (!snapshot.hasPlan()) {
            terminal.println("当前没有待办计划");
        } else {
            terminal.println("待办 · " + snapshot.completed() + "/" + snapshot.plan().items().size()
                    + " 完成");
            for (PlanItem item : snapshot.plan().items()) {
                terminal.println("  " + planMark(item) + " " + item.content());
            }
        }
        VerificationPayload verification = snapshot.verification();
        if (verification != null) {
            terminal.println("验证: " + verificationStatus(verification) + " · "
                    + verification.command() + " · " + verification.durationMs() + "ms");
        }
    }

    private static String planMark(PlanItem item) {
        return switch (item.status()) {
            case DONE -> "[x]";
            case IN_PROGRESS -> "[>]";
            case PENDING -> "[ ]";
        };
    }

    private static String verificationStatus(VerificationPayload verification) {
        if (verification.passed()) {
            return "通过";
        }
        return verification.timedOut() ? "超时" : "未通过";
    }

    private ChatSession undo(ChatSession session, CliTerminal terminal) {
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

    private static String mark(String kind) {
        return switch (kind) {
            case "ADDED" -> "+";
            case "DELETED" -> "-";
            default -> "~";
        };
    }

    private void compact(ChatSession session, CliTerminal terminal) {
        ManualCompactionView view = compaction.compact(session.identity().id());
        terminal.println(view.message());
        if (view.summary() != null) {
            terminal.println(CliText.oneLine(view.summary(), 200));
        }
    }

    private ChatSession changeModel(ChatSession session, String modelId, CliTerminal terminal) {
        if (modelId.isBlank()) {
            terminal.println("当前模型: " + session.config().modelProfileId()
                    + " · 思考 " + session.config().thinkingLevel());
            return session;
        }
        ChatSession updated = settings.changeModel(new ChangeSessionModelCommand(
                session.identity().id(), modelId, null));
        terminal.println("已切换模型: " + updated.config().modelProfileId()
                + " · 思考 " + updated.config().thinkingLevel());
        return updated;
    }

    private void printTree(ChatSession session, CliTerminal terminal) {
        List<SessionEntry> entries = sessions.branch(session.identity().id());
        if (entries.isEmpty()) {
            terminal.println("（空会话）");
            return;
        }
        for (SessionEntry entry : entries) {
            terminal.println("#" + entry.seq() + "  " + CliProjection.summary(entry));
        }
    }

    private void printSession(ChatSession session, CliTerminal terminal) {
        ChatSession current = sessions.require(session.identity().id().toString());
        terminal.println("会话: " + current.identity().id());
        terminal.println("标题: " + current.title());
        terminal.println("状态: " + current.status() + " · 模型 " + current.config().modelProfileId()
                + " · 思考 " + current.config().thinkingLevel());
        terminal.println("目录: " + current.config().cwd());
    }

    private static String helpText() {
        return """
                命令:
                  /help          显示帮助
                  /exit, /quit   退出
                  /abort         取消运行中的任务（运行期间输入有效）
                  /compact       手动压缩当前上下文
                  /model [ID]    查看或切换模型
                  /tree          查看当前分支 entry
                  /session       查看会话信息
                  /diff          查看最近一次运行的文件改动
                  /plan          查看待办与最近一次验证
                  /undo          撤销上一轮（还原文件并回退对话）
                  /skill:名称    以技能方式发送消息""";
    }
}
