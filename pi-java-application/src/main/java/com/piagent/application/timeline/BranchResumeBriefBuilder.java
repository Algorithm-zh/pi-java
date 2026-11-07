package com.piagent.application.timeline;

import com.piagent.domain.governance.model.ApprovalStatus;
import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.message.TextBlock;
import com.piagent.domain.session.model.message.UserMessage;
import com.piagent.domain.session.model.payload.ApprovalPayload;
import com.piagent.domain.session.model.payload.GitCheckpointPayload;
import com.piagent.domain.session.model.payload.MessagePayload;
import com.piagent.domain.session.model.payload.VerificationPayload;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 只用结构数据生成分支切换简报，不调用 LLM，也不包含文件内容或完整验证输出。
 */
@Component
public class BranchResumeBriefBuilder {

    private static final int MAX_SUMMARY = 400;
    private static final int MAX_NOTE = 200;
    private static final int MAX_INTENT = 80;
    private static final int MAX_DETAIL_PATHS = 50;

    /**
     * 校验备注长度。
     *
     * @param note 用户备注，可为 null
     */
    public void validateNote(String note) {
        requireNote(note);
    }

    /**
     * 生成切换简报。
     *
     * @param branchEntryId 发起切换的简报 entry
     * @param left 离开分支的尾段
     * @param target 目标分支的尾段
     * @param changedFiles 计划改动的工作区路径
     * @param fromTreeSha 离开侧实时树
     * @param toTreeSha 目标树
     * @param note 用户备注，可为 null
     * @return 摘要与 details
     */
    public BranchResumeBrief build(EntryId branchEntryId, List<SessionEntry> left,
            List<SessionEntry> target, List<String> changedFiles, String fromTreeSha,
            String toTreeSha, String note) {
        String cleanNote = requireNote(note);
        Scan leftScan = scan(left);
        Scan targetScan = scan(target);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("kind", "resume");
        details.put("branchEntryId", branchEntryId.toString());
        putIfPresent(details, "fromTreeSha", fromTreeSha);
        putIfPresent(details, "toTreeSha", toTreeSha);
        details.put("leftEntries", left.size());
        details.put("leftRuns", leftScan.runs());
        details.put("targetEntries", target.size());
        details.put("targetRuns", targetScan.runs());
        details.put("restoredFiles", changedFiles.size());
        details.put("restoredPaths", changedFiles.stream().limit(MAX_DETAIL_PATHS).toList());
        details.put("deniedApprovals", targetScan.denied());
        details.put("failedVerifications", targetScan.failed());
        return new BranchResumeBrief(render(leftScan, targetScan, changedFiles, cleanNote),
                Collections.unmodifiableMap(details));
    }

    private String requireNote(String note) {
        String cleanNote = note == null ? "" : note.strip();
        if (cleanNote.length() > MAX_NOTE) {
            throw new DomainException(DomainErrorCode.INVALID_ARGUMENT,
                    "备注不能超过 " + MAX_NOTE + " 字");
        }
        return cleanNote;
    }

    private Scan scan(List<SessionEntry> segment) {
        int userMessages = 0;
        int runs = 0;
        int denied = 0;
        int failed = 0;
        String lastIntent = "";
        Set<String> deniedTools = new LinkedHashSet<>();
        Set<String> failedCommands = new LinkedHashSet<>();
        for (SessionEntry entry : segment) {
            Object payload = entry.payload();
            if (payload instanceof MessagePayload message
                    && message.message() instanceof UserMessage user) {
                userMessages++;
                String text = userText(user);
                if (!text.isBlank()) {
                    lastIntent = text;
                }
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
        return new Scan(userMessages, runs, denied, failed, truncate(lastIntent, MAX_INTENT),
                List.copyOf(deniedTools), List.copyOf(failedCommands));
    }

    private String render(Scan left, Scan target, List<String> changedFiles, String note) {
        StringBuilder text = new StringBuilder("切回另一条分支：离开 ")
                .append(left.userMessages()).append(" 轮对话、").append(left.runs())
                .append(" 个 run；目标分支有 ").append(target.userMessages())
                .append(" 轮对话、").append(target.runs()).append(" 个 run。");
        text.append("已改动 ").append(changedFiles.size()).append(" 个文件");
        if (!changedFiles.isEmpty()) {
            text.append("（").append(joinLimit(changedFiles, 3)).append("）");
        }
        text.append("。");
        if (!target.lastIntent().isBlank()) {
            text.append("目标分支最后一次意图：").append(target.lastIntent()).append("。");
        }
        List<String> avoid = new ArrayList<>();
        target.deniedTools().stream().limit(3)
                .forEach(tool -> avoid.add(tool + "（已拒绝）"));
        target.failedCommands().stream().limit(3)
                .forEach(command -> avoid.add(command + "（已验证失败）"));
        if (!avoid.isEmpty()) {
            text.append("继续时不要重复：").append(String.join("、", avoid)).append("。");
        }
        if (!note.isBlank()) {
            text.append("备注：").append(note).append("。");
        }
        String summary = text.toString();
        return summary.length() <= MAX_SUMMARY ? summary
                : summary.substring(0, MAX_SUMMARY - 1) + "…";
    }

    private String userText(UserMessage user) {
        StringBuilder text = new StringBuilder();
        for (var block : user.content()) {
            if (block instanceof TextBlock content) {
                text.append(content.text());
            }
        }
        return text.toString().strip();
    }

    private void putIfPresent(Map<String, Object> details, String key, String value) {
        if (value != null && !value.isBlank()) {
            details.put(key, value);
        }
    }

    private String joinLimit(List<String> values, int limit) {
        String joined = String.join("、", values.stream().limit(limit).toList());
        return values.size() > limit ? joined + " 等" : joined;
    }

    private String truncate(String text, int limit) {
        return text.length() <= limit ? text : text.substring(0, limit - 1) + "…";
    }

    private record Scan(int userMessages, int runs, int denied, int failed, String lastIntent,
            List<String> deniedTools, List<String> failedCommands) {
    }
}
