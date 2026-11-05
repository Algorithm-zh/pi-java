package com.piagent.application.timeline;

import com.piagent.domain.governance.model.ApprovalStatus;
import com.piagent.domain.plan.model.PlanItem;
import com.piagent.domain.plan.model.PlanItemStatus;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.message.UserMessage;
import com.piagent.domain.session.model.payload.ApprovalPayload;
import com.piagent.domain.session.model.payload.GitCheckpointPayload;
import com.piagent.domain.session.model.payload.MessagePayload;
import com.piagent.domain.session.model.payload.PlanPayload;
import com.piagent.domain.session.model.payload.VerificationPayload;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 只用结构数据生成回溯简报，不调用 LLM，也不包含文件内容或完整验证输出。
 */
@Component
public class RewindBriefBuilder {

    private static final int MAX_SUMMARY = 400;
    private static final int MAX_NOTE = 200;

    private final DateTimeFormatter timeFormat;

    /**
     * 创建构建器。
     *
     * @param clock 时钟，提供渲染时间的时区
     */
    public RewindBriefBuilder(Clock clock) {
        this.timeFormat = DateTimeFormatter.ofPattern("HH:mm").withZone(clock.getZone());
    }

    /**
     * 生成简报。
     *
     * @param checkpointEntry 目标检查点 entry，payload 必须是 GitCheckpointPayload（调用方已校验）
     * @param discarded 将被丢弃的 entry；只扫描这里出现的 entry，检查点之前创建且未被后续 run
     *        更新的计划不会出现在简报中
     * @param restoredFiles 将还原的路径
     * @param note 用户备注，可为 null
     * @return 摘要与 details
     */
    public RewindBrief build(SessionEntry checkpointEntry, List<SessionEntry> discarded,
            List<String> restoredFiles, String note) {
        String cleanNote = requireNote(note);
        GitCheckpointPayload checkpoint = (GitCheckpointPayload) checkpointEntry.payload();
        Scan scan = scan(discarded);
        List<String> unfinished = unfinished(discarded);
        Facts facts = new Facts(scan.userMessages(), scan.runs(), scan.denied(), scan.failed(),
                restoredFiles.size(), unfinished, scan.deniedTools(), scan.failedCommands(),
                cleanNote);
        String summary = render(checkpointEntry, facts);
        return new RewindBrief(summary, details(checkpointEntry, checkpoint, discarded, facts));
    }

    private String requireNote(String note) {
        String cleanNote = note == null ? "" : note.strip();
        if (cleanNote.length() > MAX_NOTE) {
            throw new DomainException(DomainErrorCode.INVALID_ARGUMENT, "备注不能超过 " + MAX_NOTE + " 字");
        }
        return cleanNote;
    }

    private Scan scan(List<SessionEntry> discarded) {
        int userMessages = 0;
        int runs = 0;
        Set<String> deniedTools = new LinkedHashSet<>();
        Set<String> failedCommands = new LinkedHashSet<>();
        int denied = 0;
        int failed = 0;
        for (SessionEntry entry : discarded) {
            Object payload = entry.payload();
            if (payload instanceof MessagePayload message
                    && message.message() instanceof UserMessage) {
                userMessages++;
            } else if (payload instanceof GitCheckpointPayload) {
                runs++;
            } else if (payload instanceof ApprovalPayload approval
                    && ApprovalStatus.DENIED.name().equalsIgnoreCase(approval.decision())) {
                denied++;
                deniedTools.add(approval.toolName());
            } else if (payload instanceof VerificationPayload verification
                    && !verification.passed()) {
                failed++;
                failedCommands.add(verification.command());
            }
        }
        return new Scan(userMessages, runs, denied, failed, List.copyOf(deniedTools),
                List.copyOf(failedCommands));
    }

    private List<String> unfinished(List<SessionEntry> discarded) {
        PlanPayload latestPlan = null;
        for (SessionEntry entry : discarded) {
            if (entry.payload() instanceof PlanPayload plan) {
                latestPlan = plan;
            }
        }
        return latestPlan == null ? List.of() : latestPlan.items().stream()
                .filter(item -> item.status() != PlanItemStatus.DONE)
                .map(PlanItem::content)
                .toList();
    }

    private Map<String, Object> details(SessionEntry checkpointEntry,
            GitCheckpointPayload checkpoint, List<SessionEntry> discarded, Facts facts) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("kind", "rewind");
        details.put("checkpointEntryId", checkpointEntry.id().toString());
        details.put("runId", checkpoint.runId());
        details.put("discardedEntries", discarded.size());
        details.put("discardedRuns", facts.runs());
        details.put("restoredFiles", facts.restoredFiles());
        details.put("deniedApprovals", facts.denied());
        details.put("failedVerifications", facts.failed());
        details.put("unfinishedPlanItems", facts.unfinished().size());
        return Collections.unmodifiableMap(details);
    }

    private String render(SessionEntry checkpointEntry, Facts facts) {
        StringBuilder text = new StringBuilder();
        text.append("从 ").append(timeFormat.format(checkpointEntry.createdAt()))
                .append(" 的决策点回退：丢弃 ").append(facts.userMessages()).append(" 轮对话、")
                .append(facts.denied()).append(" 次被拒绝的审批、").append(facts.failed())
                .append(" 次失败的验证。");
        text.append("已还原 ").append(facts.restoredFiles()).append(" 个文件");
        if (!facts.unfinished().isEmpty()) {
            text.append("；未完成：").append(joinLimit(facts.unfinished(), 3));
        }
        text.append("。");
        List<String> avoid = new ArrayList<>();
        facts.deniedTools().stream().limit(3).forEach(tool -> avoid.add(tool + "（已拒绝）"));
        facts.failedCommands().stream().limit(3)
                .forEach(command -> avoid.add(command + "（已验证失败）"));
        if (!avoid.isEmpty()) {
            text.append("继续时不要重复：").append(String.join("、", avoid)).append("。");
        }
        if (!facts.note().isBlank()) {
            text.append("备注：").append(facts.note()).append("。");
        }
        String summary = text.toString();
        return summary.length() <= MAX_SUMMARY ? summary
                : summary.substring(0, MAX_SUMMARY - 1) + "…";
    }

    private String joinLimit(List<String> values, int limit) {
        String joined = String.join("、", values.stream().limit(limit).toList());
        return values.size() > limit ? joined + " 等" : joined;
    }

    private record Scan(int userMessages, int runs, int denied, int failed,
            List<String> deniedTools, List<String> failedCommands) {
    }

    private record Facts(int userMessages, int runs, int denied, int failed, int restoredFiles,
            List<String> unfinished, List<String> deniedTools, List<String> failedCommands,
            String note) {
    }
}
