package com.piagent.trigger.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.piagent.application.timeline.BranchResumeResult;
import com.piagent.application.timeline.SessionBranchComparison;
import com.piagent.application.timeline.SessionBranchComparisonService;
import com.piagent.application.timeline.SessionBranchResumeService;
import com.piagent.application.timeline.SessionRewindResult;
import com.piagent.application.timeline.SessionRewindService;
import com.piagent.application.timeline.SessionTimeline;
import com.piagent.application.timeline.SessionTimelineService;
import com.piagent.domain.git.model.GitConflict;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.SessionConfig;
import com.piagent.domain.session.model.SessionId;
import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code /timeline}、{@code /compare}、{@code /rewind} 的输出与安全边界。
 */
class CliTimelineSupportTest {

    private static final SessionId SESSION_ID = SessionId.parse("01TST000000000000000000001");
    private static final EntryId CHECKPOINT_A = EntryId.parse("01TST0000000000000000000A1");
    private static final EntryId CHECKPOINT_B = EntryId.parse("01TST0000000000000000000B1");
    private static final EntryId BRIEF = EntryId.parse("01TST0000000000000000000C1");
    private static final EntryId FORK = EntryId.parse("01TST0000000000000000000D1");
    private static final Instant NOW = Instant.parse("2026-11-06T10:00:00Z");
    private static final Path WORKSPACE = Path.of(System.getProperty("java.io.tmpdir"), "pi-test-workspace");

    private final CliSessionService sessions = mock(CliSessionService.class);
    private final SessionTimelineService timelines = mock(SessionTimelineService.class);
    private final SessionBranchComparisonService comparisons =
            mock(SessionBranchComparisonService.class);
    private final SessionRewindService rewinds = mock(SessionRewindService.class);
    private final SessionBranchResumeService resumes = mock(SessionBranchResumeService.class);
    private final CliTimelineSupport support =
            new CliTimelineSupport(sessions, timelines, comparisons, rewinds, resumes);
    private final ChatSession session = ChatSession.createRoot(SESSION_ID, "时间线测试",
            SessionConfig.forWorkspace(WORKSPACE, "fake-demo"));
    private final StringWriter out = new StringWriter();

    @BeforeEach
    void setUp() {
        out.getBuffer().setLength(0);
    }

    @Test
    void printsTimelineWithNumberingAndCurrentMark() {
        when(timelines.timeline(SESSION_ID)).thenReturn(timeline(
                runNode(CHECKPOINT_A, false, true, "补测试", 2, 7, 1, 0, 3),
                rewindNode(),
                runNode(CHECKPOINT_B, true, true, "继续补测试", 1, 3, 0, 0, 1)));

        support.printTimeline(session, terminal());

        assertThat(out.toString())
                .contains("3 个决策点")
                .contains(" 1. 11-06")
                .contains("补测试 · 2 轮 · 7 工具 · 1 拒绝 · 0 验证失败 · 3 文件")
                .contains(" 2. ")
                .contains("回退 · 回退到运行前")
                .contains("* 3. ")
                .contains("继续补测试 · 1 轮");
    }

    @Test
    void comparesLatestRewindByDefault() {
        when(timelines.timeline(SESSION_ID)).thenReturn(timeline(
                runNode(CHECKPOINT_B, true, true, "继续补测试", 1, 3, 0, 0, 1),
                rewindNode()));
        when(comparisons.compare(SESSION_ID, BRIEF)).thenReturn(comparison());

        support.compare(session, "", terminal());

        assertThat(out.toString())
                .contains("对比 · 另一条分支 vs 当前分支")
                .contains("另一条分支：2 个 run，改过 2 个文件")
                .contains("另一条分支 · 2 个 run")
                .contains("被拒绝: write")
                .contains("失败验证: mvn test")
                .contains("未完成: 补测试")
                .contains("仅另一条分支: A.java")
                .contains("仅当前分支: B.java")
                .contains("两边都改过: C.java");
        verify(comparisons).compare(SESSION_ID, BRIEF);
    }

    @Test
    void comparesLatestResumeByDefault() {
        when(timelines.timeline(SESSION_ID)).thenReturn(timeline(
                runNode(CHECKPOINT_B, true, true, "继续补测试", 1, 3, 0, 0, 1),
                resumeNode()));
        when(comparisons.compare(SESSION_ID, BRIEF)).thenReturn(comparison());

        support.compare(session, "", terminal());

        assertThat(out.toString()).contains("对比 · 另一条分支 vs 当前分支");
        verify(comparisons).compare(SESSION_ID, BRIEF);
    }

    @Test
    void printsResumeNodeLabel() {
        when(timelines.timeline(SESSION_ID)).thenReturn(timeline(resumeNode()));

        support.printTimeline(session, terminal());

        assertThat(out.toString()).contains("切回 · 切回另一条分支");
    }

    @Test
    void rejectsNonBranchNodeForCompare() {
        when(timelines.timeline(SESSION_ID)).thenReturn(timeline(
                runNode(CHECKPOINT_A, true, true, "补测试", 1, 3, 0, 0, 1)));

        support.compare(session, "1", terminal());

        assertThat(out.toString()).contains("不是分支节点");
        verifyNoInteractions(comparisons);
    }

    @Test
    void rejectsInvalidNodeNumber() {
        when(timelines.timeline(SESSION_ID)).thenReturn(timeline(
                runNode(CHECKPOINT_A, true, true, "补测试", 1, 3, 0, 0, 1)));

        support.compare(session, "9", terminal());
        support.rewind(session, "0", terminal());

        assertThat(out.toString()).contains("序号无效");
        verifyNoInteractions(comparisons, rewinds);
    }

    @Test
    void rewindRequiresNumberAndInteractiveTerminal() {
        when(timelines.timeline(SESSION_ID)).thenReturn(timeline(
                runNode(CHECKPOINT_A, true, true, "补测试", 1, 3, 0, 0, 1)));
        when(rewinds.rewind(SESSION_ID, CHECKPOINT_A, true, null)).thenReturn(preview(true));

        support.rewind(session, "", terminal());
        assertThat(out.toString()).contains("请指定要回退的序号");

        out.getBuffer().setLength(0);
        ChatSession result = support.rewind(session, "1", terminal());

        assertThat(result).isSameAs(session);
        assertThat(out.toString()).contains("当前不是交互终端，已取消回退");
        verify(rewinds, never()).rewind(SESSION_ID, CHECKPOINT_A, false, null);
    }

    @Test
    void rewindCancelsOnBlankAnswer() {
        when(timelines.timeline(SESSION_ID)).thenReturn(timeline(
                runNode(CHECKPOINT_A, true, true, "补测试", 1, 3, 0, 0, 1)));
        when(rewinds.rewind(SESSION_ID, CHECKPOINT_A, true, null)).thenReturn(preview(true));

        ChatSession result = support.rewind(session, "1", confirmTerminal(""));

        assertThat(result).isSameAs(session);
        assertThat(out.toString())
                .contains("将回退 · 丢弃 3 条记录 / 1 个 run · 还原 1 个文件")
                .contains("已取消回退");
        verify(rewinds, never()).rewind(SESSION_ID, CHECKPOINT_A, false, null);
    }

    @Test
    void rewindExecutesAfterConfirmation() {
        when(timelines.timeline(SESSION_ID)).thenReturn(timeline(
                runNode(CHECKPOINT_A, true, true, "补测试", 1, 3, 0, 0, 1)));
        when(rewinds.rewind(SESSION_ID, CHECKPOINT_A, true, null)).thenReturn(preview(true));
        when(rewinds.rewind(SESSION_ID, CHECKPOINT_A, false, null)).thenReturn(executed());
        when(sessions.require(SESSION_ID.toString())).thenReturn(session);

        ChatSession result = support.rewind(session, "1", confirmTerminal("y"));

        assertThat(result).isSameAs(session);
        assertThat(out.toString())
                .contains("已回退 · 还原 1 个文件 · 丢弃 3 条记录 / 1 个 run")
                .contains("  - a.txt");
    }

    @Test
    void rewindStopsWhenPreviewReportsConflicts() {
        SessionRewindResult conflict = new SessionRewindResult(true, false, false,
                List.of("a.txt"), List.of(), List.of(new GitConflict("a.txt", "已被修改")),
                null, 0, 0, "");
        when(timelines.timeline(SESSION_ID)).thenReturn(timeline(
                runNode(CHECKPOINT_A, true, true, "补测试", 1, 3, 0, 0, 1)));
        when(rewinds.rewind(SESSION_ID, CHECKPOINT_A, true, null)).thenReturn(conflict);

        ChatSession result = support.rewind(session, "1", confirmTerminal("y"));

        assertThat(result).isSameAs(session);
        assertThat(out.toString())
                .contains("! a.txt · 已被修改")
                .contains("存在冲突，不能回退");
        verify(rewinds, never()).rewind(SESSION_ID, CHECKPOINT_A, false, null);
    }

    @Test
    void rewindRefusesNonRewindableNode() {
        when(timelines.timeline(SESSION_ID)).thenReturn(timeline(
                runNode(CHECKPOINT_A, true, false, "补测试", 1, 3, 0, 0, 1)));

        support.rewind(session, "1", confirmTerminal("y"));

        assertThat(out.toString()).contains("当前不能回退");
        verifyNoInteractions(rewinds);
    }

    @Test
    void resumePreviewsAndExecutesAfterConfirmation() {
        when(timelines.timeline(SESSION_ID)).thenReturn(timeline(resumeNode()));
        when(resumes.resume(SESSION_ID, BRIEF, true, null)).thenReturn(resumePreview());
        when(resumes.resume(SESSION_ID, BRIEF, false, null)).thenReturn(resumeExecuted());
        when(sessions.require(SESSION_ID.toString())).thenReturn(session);

        ChatSession result = support.resume(session, "1", confirmTerminal("y"));

        assertThat(result).isSameAs(session);
        assertThat(out.toString())
                .contains("将切换 · 离开 2 条记录 / 1 个 run · 目标分支 4 条记录 / 2 个 run · 改动 1 个文件")
                .contains("切回目标分支")
                .contains("已切换 · 改动 1 个文件")
                .contains("  - b.txt");
        verify(resumes).resume(SESSION_ID, BRIEF, false, null);
    }

    @Test
    void resumeRejectsNonInteractiveTerminal() {
        when(timelines.timeline(SESSION_ID)).thenReturn(timeline(resumeNode()));
        when(resumes.resume(SESSION_ID, BRIEF, true, null)).thenReturn(resumePreview());

        ChatSession result = support.resume(session, "", terminal());

        assertThat(result).isSameAs(session);
        assertThat(out.toString()).contains("当前不是交互终端，已取消切换");
        verify(resumes, never()).resume(SESSION_ID, BRIEF, false, null);
    }

    @Test
    void resumeSkipsConfirmationWhenTargetUnavailable() {
        when(timelines.timeline(SESSION_ID)).thenReturn(timeline(resumeNode()));
        BranchResumeResult unavailable = new BranchResumeResult(true, false, false, true,
                "目标分支缺少工作区快照", null, List.of(), List.of(), List.of(), null, 2, 1, 4, 2,
                "");
        when(resumes.resume(SESSION_ID, BRIEF, true, null)).thenReturn(unavailable);

        CliTerminal terminal = CliTerminal.forTui(new PrintWriter(out, true), prompt -> {
            throw new AssertionError("目标不可用时不应询问确认");
        });
        support.resume(session, "1", terminal);

        assertThat(out.toString()).contains("目标分支缺少工作区快照");
        verify(resumes, never()).resume(SESSION_ID, BRIEF, false, null);
    }

    @Test
    void compareAcceptsResumeNode() {
        when(timelines.timeline(SESSION_ID)).thenReturn(timeline(resumeNode()));
        when(comparisons.compare(SESSION_ID, BRIEF)).thenReturn(comparison());

        support.compare(session, "1", terminal());

        assertThat(out.toString()).contains("对比 · 另一条分支 vs 当前分支");
        verify(comparisons).compare(SESSION_ID, BRIEF);
    }

    private CliTerminal terminal() {
        return new CliTerminal(new BufferedReader(new StringReader("")),
                new PrintWriter(out, true), new PrintWriter(new StringWriter(), true));
    }

    private CliTerminal confirmTerminal(String answer) {
        return CliTerminal.forTui(new PrintWriter(out, true), prompt -> answer);
    }

    private static SessionTimeline timeline(SessionTimeline.Node... nodes) {
        return new SessionTimeline(true, null, List.of(nodes));
    }

    private static SessionTimeline.Node runNode(EntryId checkpoint, boolean current,
            boolean rewindable, String intent, int turns, int tools, int denied, int failed,
            int files) {
        SessionTimeline.Stats stats = new SessionTimeline.Stats(
                new SessionTimeline.PlanSummary(0, 0),
                new SessionTimeline.ApprovalSummary(denied, denied),
                new SessionTimeline.VerificationSummary(failed, failed),
                new SessionTimeline.ChangeSummary(files, files, 12, 3));
        SessionTimeline.RunEvidence evidence = new SessionTimeline.RunEvidence(intent, stats,
                "completed", turns, tools, List.of(), List.of(), List.of(), List.of());
        return new SessionTimeline.Node(SessionTimeline.NodeKind.RUN, checkpoint, "run", NOW,
                current, rewindable, evidence, null);
    }

    private static SessionTimeline.Node rewindNode() {
        return new SessionTimeline.Node(SessionTimeline.NodeKind.REWIND, null, "run", NOW, false,
                false, null, new SessionTimeline.BranchEvidence(BRIEF, "回退到运行前", 3, 1));
    }

    private static SessionTimeline.Node resumeNode() {
        return new SessionTimeline.Node(SessionTimeline.NodeKind.RESUME, null, "run", NOW, true,
                false, null, new SessionTimeline.BranchEvidence(BRIEF, "切回另一条分支", 4, 2));
    }

    private static SessionBranchComparison comparison() {
        return new SessionBranchComparison(true, BRIEF, FORK, NOW,
                side("补测试", "A.java", 2), side("继续补测试", "B.java", 1),
                new SessionBranchComparison.FileDifference(List.of("A.java"), List.of("B.java"),
                        List.of("C.java"), false),
                "另一条分支：2 个 run，改过 2 个文件。");
    }

    private static SessionBranchComparison.BranchSide side(String intent, String path, int runs) {
        return new SessionBranchComparison.BranchSide(CHECKPOINT_A, List.of(intent), runs, runs,
                2, 9, List.of("completed"),
                new SessionBranchComparison.ApprovalStats(1, 1, List.of("write")),
                new SessionBranchComparison.VerificationStats(1, 1, List.of("mvn test")),
                new SessionBranchComparison.PlanStats(2, 1, List.of("补测试")),
                new SessionBranchComparison.ChangeStats(1, 2, 12, 3),
                List.of(new SessionBranchComparison.FileSummary(path, 8, 2, 1)));
    }

    private static SessionRewindResult preview(boolean restorable) {
        return new SessionRewindResult(true, false, restorable, List.of("a.txt"), List.of(),
                List.of(), null, 3, 1, "将丢弃旧分支");
    }

    private static SessionRewindResult executed() {
        return new SessionRewindResult(false, true, true, List.of("a.txt"), List.of("a.txt"),
                List.of(), BRIEF, 3, 1, "已回退");
    }

    private static BranchResumeResult resumePreview() {
        return new BranchResumeResult(true, false, true, true, "", CHECKPOINT_B,
                List.of("b.txt"), List.of(), List.of(), null, 2, 1, 4, 2, "切回目标分支");
    }

    private static BranchResumeResult resumeExecuted() {
        return new BranchResumeResult(false, true, true, true, "", CHECKPOINT_B,
                List.of("b.txt"), List.of("b.txt"), List.of(), BRIEF, 2, 1, 4, 2, "已切换");
    }
}