package com.piagent.application.timeline;

import com.piagent.domain.governance.model.ApprovalStatus;
import com.piagent.domain.plan.model.PlanItem;
import com.piagent.domain.plan.model.PlanItemStatus;
import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.message.TextBlock;
import com.piagent.domain.session.model.message.UserMessage;
import com.piagent.domain.session.model.payload.ApprovalPayload;
import com.piagent.domain.session.model.payload.BranchSummaryPayload;
import com.piagent.domain.session.model.payload.GitChangeSetPayload;
import com.piagent.domain.session.model.payload.GitCheckpointPayload;
import com.piagent.domain.session.model.payload.MessagePayload;
import com.piagent.domain.session.model.payload.PlanPayload;
import com.piagent.domain.session.model.payload.RunSummaryPayload;
import com.piagent.domain.session.model.payload.VerificationPayload;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

/** 把一段分支 entry 投影成对比单侧，并生成文件差异与确定性摘要。 */
@Component
public class BranchComparisonProjector {

    private static final int MAX_INTENTS = 20;
    private static final int MAX_INTENT_CHARS = 400;
    private static final int MAX_LIST = 10;
    private static final int MAX_FILES = 100;
    private static final int MAX_SUMMARY = 500;
    private static final int MAX_SUMMARY_FILES = 5;

    /**
     * 投影分叉点之后的一段分支。
     *
     * <p>回退简报（{@code BRANCH_SUMMARY}）不参与统计，避免把“被放弃的过去”
     * 算进当前分支。</p>
     *
     * @param segment 分叉点之后到该侧叶子的 entry
     * @param leafEntryId 该侧叶子
     * @return 单侧投影
     */
    public SessionBranchComparison.BranchSide project(List<SessionEntry> segment,
            EntryId leafEntryId) {
        Tally tally = new Tally();
        for (SessionEntry entry : segment) {
            tally.accept(entry);
        }
        return tally.toSide(leafEntryId);
    }

    /**
     * 按路径求两侧差异。
     *
     * @param abandoned 被放弃分支
     * @param current 当前分支
     * @return 排序后的差异；任一组超过 100 时标记截断
     */
    public SessionBranchComparison.FileDifference difference(
            SessionBranchComparison.BranchSide abandoned, SessionBranchComparison.BranchSide current) {
        Set<String> abandonedPaths = paths(abandoned);
        Set<String> currentPaths = paths(current);
        Set<String> onlyAbandoned = new TreeSet<>(abandonedPaths);
        onlyAbandoned.removeAll(currentPaths);
        Set<String> onlyCurrent = new TreeSet<>(currentPaths);
        onlyCurrent.removeAll(abandonedPaths);
        Set<String> both = new TreeSet<>(abandonedPaths);
        both.retainAll(currentPaths);
        boolean truncated = onlyAbandoned.size() > MAX_FILES || onlyCurrent.size() > MAX_FILES
                || both.size() > MAX_FILES;
        return new SessionBranchComparison.FileDifference(
                limit(onlyAbandoned, MAX_FILES), limit(onlyCurrent, MAX_FILES),
                limit(both, MAX_FILES), truncated);
    }

    /**
     * 生成确定性中文摘要。
     *
     * @param abandoned 被放弃分支
     * @param current 当前分支
     * @param files 文件差异
     * @return 最多 500 字的摘要
     */
    public String summary(SessionBranchComparison.BranchSide abandoned,
            SessionBranchComparison.BranchSide current,
            SessionBranchComparison.FileDifference files) {
        StringBuilder text = new StringBuilder(describe("旧分支", abandoned));
        text.append('\n').append(describe("当前分支", current));
        appendPaths(text, "旧分支独有的文件：", files.onlyAbandoned());
        appendPaths(text, "当前分支独有的文件：", files.onlyCurrent());
        return truncate(text.toString(), MAX_SUMMARY);
    }

    private String describe(String label, SessionBranchComparison.BranchSide side) {
        boolean empty = side.intents().isEmpty() && side.runs() == 0 && side.turns() == 0
                && side.files().isEmpty() && side.approvals().total() == 0
                && side.verifications().total() == 0;
        if (empty) {
            return label + "没有后续动作。";
        }
        StringBuilder text = new StringBuilder(label).append("：")
                .append(side.turns()).append(" 轮对话、")
                .append(side.runs()).append(" 个 run");
        if (side.approvals().denied() > 0) {
            text.append("、").append(side.approvals().denied()).append(" 次拒绝");
        }
        if (side.verifications().failed() > 0) {
            text.append("、").append(side.verifications().failed()).append(" 次验证失败");
        }
        text.append("，");
        if (side.files().isEmpty()) {
            text.append("没有文件改动");
        } else {
            text.append("改过 ").append(side.files().size()).append(" 个文件（+")
                    .append(side.changes().additions()).append(" −")
                    .append(side.changes().deletions()).append("）");
        }
        return text.append("。").toString();
    }

    private void appendPaths(StringBuilder text, String label, List<String> paths) {
        if (paths.isEmpty()) {
            return;
        }
        text.append('\n').append(label);
        int shown = Math.min(paths.size(), MAX_SUMMARY_FILES);
        for (int index = 0; index < shown; index++) {
            if (index > 0) {
                text.append('、');
            }
            text.append(paths.get(index));
        }
        if (paths.size() > shown) {
            text.append(" 等 ").append(paths.size()).append(" 个");
        }
        text.append('。');
    }

    private static Set<String> paths(SessionBranchComparison.BranchSide side) {
        Set<String> paths = new LinkedHashSet<>();
        side.files().forEach(file -> paths.add(file.path()));
        return paths;
    }

    private static String userText(UserMessage user) {
        StringBuilder text = new StringBuilder();
        for (var block : user.content()) {
            if (block instanceof TextBlock content) {
                text.append(content.text());
            }
        }
        String value = text.toString().strip();
        if (value.isEmpty() && !user.content().isEmpty()) {
            return "（图片消息）";
        }
        return value;
    }

    private static List<String> limit(Set<String> values, int limit) {
        return values.stream().limit(limit).toList();
    }

    private static List<String> limit(List<String> values, int limit) {
        return values.size() <= limit ? List.copyOf(values) : List.copyOf(values.subList(0, limit));
    }

    private static String truncate(String text, int limit) {
        return text.length() <= limit ? text : text.substring(0, limit - 1) + "…";
    }

    private static final class Tally {

        private final List<String> intents = new ArrayList<>();
        private final Set<String> stopReasons = new LinkedHashSet<>();
        private final List<String> deniedTools = new ArrayList<>();
        private final List<String> failedCommands = new ArrayList<>();
        private final Map<String, int[]> files = new LinkedHashMap<>();
        private List<PlanItem> planItems = List.of();
        private int runs;
        private int completedRuns;
        private int turns;
        private int toolCalls;
        private int approvalTotal;
        private int denied;
        private int verificationTotal;
        private int failed;
        private int changeTurns;

        private void accept(SessionEntry entry) {
            Object payload = entry.payload();
            if (payload instanceof BranchSummaryPayload) {
                return;
            }
            if (payload instanceof MessagePayload message
                    && message.message() instanceof UserMessage user) {
                String text = userText(user);
                if (!text.isBlank()) {
                    intents.add(truncate(text, MAX_INTENT_CHARS));
                }
                return;
            }
            if (payload instanceof GitCheckpointPayload) {
                runs++;
                return;
            }
            if (payload instanceof RunSummaryPayload summary) {
                acceptRun(summary);
                return;
            }
            if (payload instanceof ApprovalPayload approval) {
                acceptApproval(approval);
                return;
            }
            if (payload instanceof VerificationPayload verification) {
                acceptVerification(verification);
                return;
            }
            if (payload instanceof PlanPayload plan) {
                planItems = plan.items();
                return;
            }
            if (payload instanceof GitChangeSetPayload change) {
                acceptChange(change);
            }
        }

        private void acceptRun(RunSummaryPayload summary) {
            completedRuns++;
            turns += summary.turns();
            toolCalls += summary.toolCalls();
            if (!summary.stopReason().isBlank()) {
                stopReasons.add(summary.stopReason());
            }
        }

        private void acceptApproval(ApprovalPayload approval) {
            approvalTotal++;
            if (ApprovalStatus.DENIED.name().equalsIgnoreCase(approval.decision())) {
                denied++;
                deniedTools.add(approval.toolName());
            }
        }

        private void acceptVerification(VerificationPayload verification) {
            verificationTotal++;
            if (!verification.passed()) {
                failed++;
                failedCommands.add(verification.command());
            }
        }

        private void acceptChange(GitChangeSetPayload change) {
            changeTurns++;
            for (var file : change.files()) {
                int[] totals = files.computeIfAbsent(file.path(), key -> new int[3]);
                totals[0] += file.additions();
                totals[1] += file.deletions();
                totals[2]++;
            }
        }

        private SessionBranchComparison.BranchSide toSide(EntryId leafEntryId) {
            int done = (int) planItems.stream()
                    .filter(item -> item.status() == PlanItemStatus.DONE).count();
            List<String> unfinished = planItems.stream()
                    .filter(item -> item.status() != PlanItemStatus.DONE)
                    .map(PlanItem::content).limit(MAX_LIST).toList();
            List<SessionBranchComparison.FileSummary> summaries = fileSummaries();
            int additions = files.values().stream().mapToInt(value -> value[0]).sum();
            int deletions = files.values().stream().mapToInt(value -> value[1]).sum();
            return new SessionBranchComparison.BranchSide(leafEntryId,
                    limit(intents, MAX_INTENTS), runs, completedRuns, turns, toolCalls,
                    limit(stopReasons, MAX_LIST),
                    new SessionBranchComparison.ApprovalStats(approvalTotal, denied,
                            limit(deniedTools, MAX_LIST)),
                    new SessionBranchComparison.VerificationStats(verificationTotal, failed,
                            limit(failedCommands, MAX_LIST)),
                    new SessionBranchComparison.PlanStats(planItems.size(), done, unfinished),
                    new SessionBranchComparison.ChangeStats(changeTurns, files.size(),
                            additions, deletions),
                    summaries);
        }

        private List<SessionBranchComparison.FileSummary> fileSummaries() {
            return files.entrySet().stream()
                    .limit(MAX_FILES)
                    .map(entry -> new SessionBranchComparison.FileSummary(entry.getKey(),
                            entry.getValue()[0], entry.getValue()[1], entry.getValue()[2]))
                    .toList();
        }
    }
}
