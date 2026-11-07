package com.piagent.trigger.cli;

import com.piagent.application.timeline.SessionBranchComparison;
import com.piagent.application.timeline.SessionRewindResult;
import com.piagent.application.timeline.SessionTimeline;
import com.piagent.domain.git.model.GitConflict;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 时间线、对比与回退预览的终端排版。
 *
 * <p>只做格式化，不读取数据也不做选择；所有来自服务的列表都已在应用层截断。</p>
 */
final class CliTimelineText {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());
    private static final int MAX_INTENT = 60;
    private static final int MAX_LINE = 300;
    private static final int MAX_BRIEF = 300;

    private CliTimelineText() {
    }

    static void timeline(SessionTimeline timeline, CliTerminal terminal) {
        List<SessionTimeline.Node> nodes = timeline.nodes();
        if (nodes.isEmpty()) {
            terminal.println("当前还没有决策点；完成一轮对话后可用 /timeline 查看");
            return;
        }
        terminal.println("时间线 · " + nodes.size() + " 个决策点（* 为当前节点）");
        for (int index = 0; index < nodes.size(); index++) {
            terminal.println(nodeLine(index + 1, nodes.get(index)));
        }
        terminal.println("  /compare [序号] 对比两条分支 · /rewind <序号> 回退到检查点");
    }

    static void compare(SessionBranchComparison comparison, CliTerminal terminal) {
        terminal.println("对比 · 另一条分支 vs 当前分支");
        terminal.println(comparison.summary());
        printSide("另一条分支", comparison.abandoned(), terminal);
        printSide("当前分支", comparison.current(), terminal);
        printFiles(comparison.files(), terminal);
    }

    static void preview(SessionRewindResult preview, CliTerminal terminal) {
        terminal.println("将回退 · 丢弃 " + preview.discardedEntries() + " 条记录 / "
                + preview.discardedRuns() + " 个 run · 还原 " + preview.files().size() + " 个文件");
        if (!preview.brief().isBlank()) {
            terminal.println(CliText.oneLine(preview.brief(), MAX_BRIEF));
        }
        preview.files().forEach(path -> terminal.println("  - " + path));
        for (GitConflict conflict : preview.conflicts()) {
            terminal.println("  ! " + conflict.path() + " · " + conflict.reason());
        }
        if (!preview.restorable()) {
            terminal.println("存在冲突，不能回退；请先处理上面的文件");
        }
    }

    private static String nodeLine(int number, SessionTimeline.Node node) {
        String mark = node.current() ? "*" : " ";
        String label = switch (node.kind()) {
            case RUN -> "run";
            case REWIND -> "回退";
            case RESUME -> "切回";
        };
        String head = mark + " " + number + ". " + TIME.format(node.at()) + " · " + label;
        if (node.kind() != SessionTimeline.NodeKind.RUN) {
            return head + " · " + CliText.oneLine(node.branch().brief(), MAX_INTENT);
        }
        SessionTimeline.RunEvidence run = node.run();
        if (run == null) {
            return head;
        }
        String intent = CliText.oneLine(run.intent(), MAX_INTENT);
        return head + " · " + (intent.isBlank() ? "（未记录意图）" : intent) + " · " + facts(run);
    }

    private static String facts(SessionTimeline.RunEvidence run) {
        SessionTimeline.Stats stats = run.stats();
        return run.turns() + " 轮 · " + run.toolCalls() + " 工具 · "
                + stats.approvals().denied() + " 拒绝 · "
                + stats.verification().failed() + " 验证失败 · "
                + stats.changes().files() + " 文件";
    }

    private static void printSide(String label, SessionBranchComparison.BranchSide side,
            CliTerminal terminal) {
        terminal.println(label + " · " + side.runs() + " 个 run / " + side.turns() + " 轮对话 / "
                + side.toolCalls() + " 次工具 / " + side.approvals().denied() + " 次拒绝 / "
                + side.verifications().failed() + " 次验证失败 / " + side.changes().files()
                + " 个文件 +" + side.changes().additions() + " -" + side.changes().deletions());
        if (isEmpty(side)) {
            terminal.println("  没有后续动作");
            return;
        }
        if (!side.intents().isEmpty()) {
            String intents = side.intents().stream()
                    .map(text -> CliText.oneLine(text, 80))
                    .collect(Collectors.joining(" / "));
            terminal.println("  意图: " + CliText.oneLine(intents, 240));
        }
        if (!side.approvals().deniedTools().isEmpty()) {
            terminal.println("  被拒绝: " + join(side.approvals().deniedTools()));
        }
        if (!side.verifications().failedCommands().isEmpty()) {
            terminal.println("  失败验证: " + join(side.verifications().failedCommands()));
        }
        if (!side.plan().unfinished().isEmpty()) {
            terminal.println("  未完成: " + join(side.plan().unfinished()));
        }
    }

    private static boolean isEmpty(SessionBranchComparison.BranchSide side) {
        return side.intents().isEmpty() && side.files().isEmpty() && side.runs() == 0
                && side.plan().total() == 0;
    }

    private static void printFiles(SessionBranchComparison.FileDifference files,
            CliTerminal terminal) {
        if (files.onlyAbandoned().isEmpty() && files.onlyCurrent().isEmpty()
                && files.both().isEmpty()) {
            terminal.println("文件差异 · 两边都没有改过文件");
            return;
        }
        terminal.println("文件差异");
        printPaths("仅另一条分支", files.onlyAbandoned(), terminal);
        printPaths("仅当前分支", files.onlyCurrent(), terminal);
        printPaths("两边都改过", files.both(), terminal);
        if (files.truncated()) {
            terminal.println("  …（列表已截断，完整内容见 Web 对比面板）");
        }
    }

    private static void printPaths(String label, List<String> paths, CliTerminal terminal) {
        if (paths.isEmpty()) {
            return;
        }
        terminal.println("  " + label + ": " + join(paths));
    }

    private static String join(List<String> values) {
        return CliText.oneLine(String.join("、", values), MAX_LINE);
    }
}