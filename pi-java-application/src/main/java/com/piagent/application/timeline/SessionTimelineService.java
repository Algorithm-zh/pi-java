package com.piagent.application.timeline;

import com.piagent.domain.governance.model.ApprovalStatus;
import com.piagent.domain.plan.model.PlanItem;
import com.piagent.domain.plan.model.PlanItemStatus;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.SessionStatus;
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
import com.piagent.domain.session.port.SessionEntryStore;
import com.piagent.domain.session.port.SessionRepository;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/** 把当前分支投影成决策时间线，只读且不访问磁盘。 */
@Service
public class SessionTimelineService {

    private static final int MAX_INTENT = 400;

    private final SessionRepository sessions;
    private final SessionEntryStore entries;

    public SessionTimelineService(SessionRepository sessions, SessionEntryStore entries) {
        this.sessions = sessions;
        this.entries = entries;
    }

    /** 查询当前分支的时间线；空会话返回 available=false 且无节点。 */
    public SessionTimeline timeline(SessionId sessionId) {
        ChatSession session = require(sessionId);
        List<SessionEntry> branch = entries.loadBranch(sessionId, session.leafEntryId());
        if (branch.isEmpty()) {
            return new SessionTimeline(false, null, List.of());
        }
        boolean hasCheckpoint = branch.stream()
                .anyMatch(entry -> entry.payload() instanceof GitCheckpointPayload);
        boolean idle = session.status() == SessionStatus.IDLE;
        List<IndexedNode> indexed = hasCheckpoint
                ? checkpointNodes(branch, idle)
                : fallbackNodes(branch);
        if (indexed.isEmpty()) {
            return new SessionTimeline(hasCheckpoint, null, List.of());
        }
        int last = indexed.size() - 1;
        List<SessionTimeline.Node> nodes = new ArrayList<>();
        EntryId currentCheckpoint = null;
        for (int index = 0; index < indexed.size(); index++) {
            SessionTimeline.Node node = indexed.get(index).node();
            boolean current = index == last;
            if (current && indexed.get(last).node().kind() == SessionTimeline.NodeKind.RUN) {
                currentCheckpoint = node.checkpointEntryId();
            }
            nodes.add(new SessionTimeline.Node(node.kind(), node.checkpointEntryId(), node.runId(),
                    node.at(), current, node.rewindable(), node.run(), node.rewind()));
        }
        return new SessionTimeline(hasCheckpoint, currentCheckpoint, List.copyOf(nodes));
    }

    private List<IndexedNode> checkpointNodes(List<SessionEntry> branch, boolean idle) {
        List<IndexedNode> nodes = new ArrayList<>();
        for (int index = 0; index < branch.size(); index++) {
            Object payload = branch.get(index).payload();
            if (payload instanceof GitCheckpointPayload checkpoint) {
                int end = nextCheckpoint(branch, index + 1);
                nodes.add(new IndexedNode(index, runNode(branch, new RunSlice(index, end,
                        checkpoint.runId(), branch.get(index), branch.get(index), idle))));
            } else if (payload instanceof BranchSummaryPayload summary && isRewind(summary)) {
                nodes.add(new IndexedNode(index, rewindNode(branch.get(index), summary)));
            }
        }
        return nodes;
    }

    private List<IndexedNode> fallbackNodes(List<SessionEntry> branch) {
        List<IndexedNode> nodes = new ArrayList<>();
        int start = 0;
        for (int index = 0; index < branch.size(); index++) {
            if (branch.get(index).payload() instanceof RunSummaryPayload summary) {
                int userIndex = firstUserMessage(branch, start, index + 1);
                SessionEntry at = branch.get(userIndex >= 0 ? userIndex : index);
                nodes.add(new IndexedNode(index, runNode(branch, new RunSlice(start,
                        index + 1, summary.runId(), null, at, false))));
                start = index + 1;
            }
        }
        if (start < branch.size()) {
            int userIndex = firstUserMessage(branch, start, branch.size());
            if (userIndex >= 0) {
                nodes.add(new IndexedNode(branch.size() - 1, runNode(branch, new RunSlice(start,
                        branch.size(), "", null, branch.get(userIndex), false))));
            }
        }
        for (int index = 0; index < branch.size(); index++) {
            if (branch.get(index).payload() instanceof BranchSummaryPayload summary
                    && isRewind(summary)) {
                nodes.add(new IndexedNode(index, rewindNode(branch.get(index), summary)));
            }
        }
        nodes.sort(Comparator.comparingInt(IndexedNode::index));
        return nodes;
    }

    private SessionTimeline.Node runNode(List<SessionEntry> branch, RunSlice slice) {
        SessionTimeline.RunEvidence evidence = evidence(branch, slice.start(), slice.end(),
                slice.runId(), slice.checkpointEntry() != null);
        return new SessionTimeline.Node(SessionTimeline.NodeKind.RUN,
                slice.checkpointEntry() == null ? null : slice.checkpointEntry().id(), slice.runId(),
                slice.atEntry().createdAt(), false, slice.rewindable(), evidence, null);
    }

    private SessionTimeline.RunEvidence evidence(List<SessionEntry> branch, int start, int end,
            String runId, boolean checkpointed) {
        List<ApprovalPayload> approvals = new ArrayList<>();
        List<VerificationPayload> verifications = new ArrayList<>();
        List<GitChangeSetPayload> changes = new ArrayList<>();
        String stopReason = "";
        int turns = 0;
        int toolCalls = 0;
        for (int index = start; index < end; index++) {
            Object payload = branch.get(index).payload();
            if (payload instanceof ApprovalPayload approval) {
                approvals.add(approval);
            } else if (payload instanceof VerificationPayload verification
                    && matches(verification.runId(), runId)) {
                verifications.add(verification);
            } else if (payload instanceof GitChangeSetPayload change
                    && matches(change.runId(), runId)) {
                changes.add(change);
            } else if (payload instanceof RunSummaryPayload summary
                    && matches(summary.runId(), runId)) {
                stopReason = summary.stopReason();
                turns = summary.turns();
                toolCalls = summary.toolCalls();
            }
        }
        // 计划 entry 是整份快照：节点展示其结束时的最新一份，可沿用更早的 run 或手工编辑。
        List<PlanItem> planItems = List.of();
        for (int index = Math.min(end, branch.size()) - 1; index >= 0; index--) {
            if (branch.get(index).payload() instanceof PlanPayload plan) {
                planItems = plan.items();
                break;
            }
        }
        SessionTimeline.Stats stats = stats(planItems, approvals, verifications, changes);
        return new SessionTimeline.RunEvidence(intent(branch, start, end, checkpointed), stats,
                stopReason, turns, toolCalls, planItems, List.copyOf(approvals),
                List.copyOf(verifications), List.copyOf(changes));
    }

    private SessionTimeline.Stats stats(List<PlanItem> planItems, List<ApprovalPayload> approvals,
            List<VerificationPayload> verifications, List<GitChangeSetPayload> changes) {
        int done = (int) planItems.stream().filter(item -> item.status() == PlanItemStatus.DONE).count();
        int denied = (int) approvals.stream().filter(item -> ApprovalStatus.DENIED.name().equalsIgnoreCase(item.decision())).count();
        int failed = (int) verifications.stream().filter(item -> !item.passed()).count();
        Set<String> paths = new LinkedHashSet<>();
        int additions = 0;
        int deletions = 0;
        for (GitChangeSetPayload change : changes) {
            for (var file : change.files()) {
                paths.add(file.path());
                additions += file.additions();
                deletions += file.deletions();
            }
        }
        return new SessionTimeline.Stats(
                new SessionTimeline.PlanSummary(planItems.size(), done),
                new SessionTimeline.ApprovalSummary(approvals.size(), denied),
                new SessionTimeline.VerificationSummary(verifications.size(), failed),
                new SessionTimeline.ChangeSummary(changes.size(), paths.size(), additions, deletions));
    }

    // 触发 run 的用户文本；先在节点内向前扫描，否则回溯并遇到 run/分支边界即停止。
    private String intent(List<SessionEntry> branch, int start, int end, boolean checkpointed) {
        if (!checkpointed) {
            for (int index = start; index < end; index++) {
                String text = userTextAt(branch, index);
                if (!text.isBlank()) {
                    return truncate(text, MAX_INTENT);
                }
            }
        }
        for (int index = start - 1; index >= 0; index--) {
            Object payload = branch.get(index).payload();
            if (payload instanceof GitCheckpointPayload || payload instanceof RunSummaryPayload
                    || payload instanceof BranchSummaryPayload) {
                break;
            }
            String text = userTextAt(branch, index);
            if (!text.isBlank()) {
                return truncate(text, MAX_INTENT);
            }
        }
        return "";
    }

    private String userTextAt(List<SessionEntry> branch, int index) {
        if (branch.get(index).payload() instanceof MessagePayload message
                && message.message() instanceof UserMessage user) {
            return textOf(user);
        }
        return "";
    }

    private String textOf(UserMessage user) {
        StringBuilder text = new StringBuilder();
        for (var block : user.content()) {
            if (block instanceof TextBlock content) {
                text.append(content.text());
            }
        }
        if (text.toString().isBlank() && !user.content().isEmpty()) {
            return "（图片消息）";
        }
        return text.toString().strip();
    }

    private int firstUserMessage(List<SessionEntry> branch, int start, int end) {
        for (int index = start; index < end; index++) {
            if (branch.get(index).payload() instanceof MessagePayload message && message.message() instanceof UserMessage) {
                return index;
            }
        }
        return -1;
    }

    private int nextCheckpoint(List<SessionEntry> branch, int start) {
        for (int index = start; index < branch.size(); index++) {
            if (branch.get(index).payload() instanceof GitCheckpointPayload) {
                return index;
            }
        }
        return branch.size();
    }

    private SessionTimeline.Node rewindNode(SessionEntry entry, BranchSummaryPayload payload) {
        Map<String, Object> details = payload.details();
        return new SessionTimeline.Node(SessionTimeline.NodeKind.REWIND, null,
                text(details.get("runId")), entry.createdAt(), false, false, null,
                new SessionTimeline.RewindEvidence(payload.summary(),
                        number(details.get("discardedEntries")), number(details.get("discardedRuns"))));
    }

    private boolean isRewind(BranchSummaryPayload payload) {
        return "rewind".equals(payload.details().get("kind"));
    }

    private boolean matches(String value, String runId) {
        return runId.isEmpty() || runId.equals(value);
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private int number(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException error) {
            return 0;
        }
    }

    private String truncate(String text, int limit) {
        return text.length() <= limit ? text : text.substring(0, limit - 1) + "…";
    }

    private ChatSession require(SessionId sessionId) {
        return sessions.findById(sessionId)
                .orElseThrow(() -> new DomainException(DomainErrorCode.SESSION_NOT_FOUND, "会话不存在: " + sessionId));
    }

    private record IndexedNode(int index, SessionTimeline.Node node) { }

    private record RunSlice(int start, int end, String runId, SessionEntry checkpointEntry,
            SessionEntry atEntry, boolean rewindable) { }
}
