package com.piagent.trigger.cli;

import com.piagent.application.timeline.BranchResumeResult;
import com.piagent.application.timeline.SessionBranchComparison;
import com.piagent.application.timeline.SessionBranchComparisonService;
import com.piagent.application.timeline.SessionBranchResumeService;
import com.piagent.application.timeline.SessionRewindResult;
import com.piagent.application.timeline.SessionRewindService;
import com.piagent.application.timeline.SessionTimeline;
import com.piagent.application.timeline.SessionTimelineService;
import com.piagent.domain.session.model.ChatSession;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * chat 内置的时间线命令：{@code /timeline}、{@code /compare}、{@code /rewind}
 * 与 {@code /resume}。
 *
 * <p>四个命令都消费应用层的只读投影；{@code /rewind} 与 {@code /resume} 会写数据，
 * 且必须先预览、再在交互终端里显式确认。非交互环境一律拒绝执行。</p>
 */
@Component
public class CliTimelineSupport {

    private final CliSessionService sessions;
    private final SessionTimelineService timelines;
    private final SessionBranchComparisonService comparisons;
    private final SessionRewindService rewinds;
    private final SessionBranchResumeService resumes;

    /**
     * 创建时间线命令支持。
     *
     * @param sessions 会话加载
     * @param timelines 时间线投影
     * @param comparisons 分叉对比
     * @param rewinds 检查点回退
     * @param resumes 分支切换
     */
    public CliTimelineSupport(CliSessionService sessions, SessionTimelineService timelines,
            SessionBranchComparisonService comparisons, SessionRewindService rewinds,
            SessionBranchResumeService resumes) {
        this.sessions = sessions;
        this.timelines = timelines;
        this.comparisons = comparisons;
        this.rewinds = rewinds;
        this.resumes = resumes;
    }

    /**
     * 按顺序打印当前分支的决策点。
     *
     * @param session 当前会话
     * @param terminal 输出终端
     */
    public void printTimeline(ChatSession session, CliTerminal terminal) {
        CliTimelineText.timeline(timelines.timeline(session.identity().id()), terminal);
    }

    /**
     * 对比另一条分支与当前分支。
     *
     * @param session 当前会话
     * @param argument 时间线序号；空表示最近一次分支切换
     * @param terminal 输出终端
     */
    public void compare(ChatSession session, String argument, CliTerminal terminal) {
        SessionTimeline timeline = timelines.timeline(session.identity().id());
        SessionTimeline.Node target = branchNode(timeline, argument, terminal, "对比");
        if (target == null) {
            return;
        }
        SessionBranchComparison comparison = comparisons.compare(session.identity().id(),
                target.branch().entryId());
        if (!comparison.available()) {
            terminal.println("当前会话还没有分支切换记录，无法对比");
            return;
        }
        CliTimelineText.compare(comparison, terminal);
    }

    /**
     * 切到时间线上的另一条分支。
     *
     * @param session 当前会话
     * @param argument 时间线序号；空表示最近一条分支简报
     * @param terminal 输出终端
     * @return 切换后的会话；取消或拒绝时返回原会话
     */
    public ChatSession resume(ChatSession session, String argument, CliTerminal terminal) {
        SessionTimeline timeline = timelines.timeline(session.identity().id());
        SessionTimeline.Node target = branchNode(timeline, argument, terminal, "切换");
        if (target == null) {
            return session;
        }
        BranchResumeResult preview = resumes.resume(session.identity().id(),
                target.branch().entryId(), true, null);
        CliTimelineText.resumePreview(preview, terminal);
        if (!preview.available() || !preview.restorable()) {
            return session;
        }
        if (!terminal.interactive()) {
            terminal.println("当前不是交互终端，已取消切换；请在 chat 中手动执行");
            return session;
        }
        String answer = terminal.readLine("确认切换？输入 y 回车执行，直接回车取消: ");
        if (!confirmed(answer)) {
            terminal.println("已取消切换");
            return session;
        }
        BranchResumeResult result = resumes.resume(session.identity().id(),
                target.branch().entryId(), false, null);
        if (!result.switched()) {
            terminal.println("切换没有执行，工作区可能又被改动，请重试");
            return session;
        }
        terminal.println("已切换 · 改动 " + result.restored().size() + " 个文件");
        result.restored().forEach(path -> terminal.println("  - " + path));
        return sessions.require(session.identity().id().toString());
    }

    /**
     * 回退到时间线上的某个检查点。
     *
     * @param session 当前会话
     * @param argument 时间线序号
     * @param terminal 输出终端
     * @return 回退后的会话；取消或拒绝时返回原会话
     */
    public ChatSession rewind(ChatSession session, String argument, CliTerminal terminal) {
        SessionTimeline timeline = timelines.timeline(session.identity().id());
        SessionTimeline.Node target = checkpointNode(timeline, argument, terminal);
        if (target == null) {
            return session;
        }
        if (!target.rewindable()) {
            terminal.println("这个节点当前不能回退：只有空闲会话的检查点可以回退");
            return session;
        }
        SessionRewindResult preview = rewinds.rewind(session.identity().id(),
                target.checkpointEntryId(), true, null);
        CliTimelineText.preview(preview, terminal);
        if (!preview.restorable()) {
            return session;
        }
        if (!terminal.interactive()) {
            terminal.println("当前不是交互终端，已取消回退；请在 chat 中手动执行");
            return session;
        }
        String answer = terminal.readLine("确认回退？输入 y 回车执行，直接回车取消: ");
        if (!confirmed(answer)) {
            terminal.println("已取消回退");
            return session;
        }
        SessionRewindResult result = rewinds.rewind(session.identity().id(),
                target.checkpointEntryId(), false, null);
        if (!result.rewound()) {
            terminal.println("回退没有执行，工作区可能又被改动，请重试");
            return session;
        }
        terminal.println("已回退 · 还原 " + result.restored().size() + " 个文件 · 丢弃 "
                + result.discardedEntries() + " 条记录 / " + result.discardedRuns() + " 个 run");
        result.restored().forEach(path -> terminal.println("  - " + path));
        return sessions.require(session.identity().id().toString());
    }

    private static SessionTimeline.Node branchNode(SessionTimeline timeline, String argument,
            CliTerminal terminal, String action) {
        List<SessionTimeline.Node> nodes = timeline.nodes();
        if (argument.isBlank()) {
            for (int index = nodes.size() - 1; index >= 0; index--) {
                if (nodes.get(index).kind() != SessionTimeline.NodeKind.RUN) {
                    return nodes.get(index);
                }
            }
            terminal.println("当前会话还没有分支切换记录，无法" + action);
            return null;
        }
        SessionTimeline.Node node = nodeAt(timeline, argument, terminal);
        if (node == null) {
            return null;
        }
        if (node.kind() == SessionTimeline.NodeKind.RUN) {
            terminal.println("第 " + argument.strip() + " 个节点不是分支节点，请用 /timeline 查看");
            return null;
        }
        return node;
    }

    private static SessionTimeline.Node checkpointNode(SessionTimeline timeline, String argument,
            CliTerminal terminal) {
        if (argument.isBlank()) {
            terminal.println("请指定要回退的序号，例如 /rewind 2；先用 /timeline 查看");
            return null;
        }
        SessionTimeline.Node node = nodeAt(timeline, argument, terminal);
        if (node == null) {
            return null;
        }
        if (node.kind() != SessionTimeline.NodeKind.RUN || node.checkpointEntryId() == null) {
            terminal.println("第 " + argument.strip() + " 个节点不是可回退的检查点，请用 /timeline 查看");
            return null;
        }
        return node;
    }

    private static SessionTimeline.Node nodeAt(SessionTimeline timeline, String argument,
            CliTerminal terminal) {
        Integer number = parse(argument);
        List<SessionTimeline.Node> nodes = timeline.nodes();
        if (number == null || number < 1 || number > nodes.size()) {
            terminal.println("序号无效，请先用 /timeline 查看节点序号");
            return null;
        }
        return nodes.get(number - 1);
    }

    private static boolean confirmed(String answer) {
        if (answer == null) {
            return false;
        }
        String value = answer.strip().toLowerCase(Locale.ROOT);
        return "y".equals(value) || "yes".equals(value);
    }

    private static Integer parse(String argument) {
        try {
            return Integer.parseInt(argument.strip());
        } catch (NumberFormatException error) {
            return null;
        }
    }
}