package com.piagent.domain.session.service;

import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.EntryType;
import com.piagent.domain.session.model.ProjectedContext;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.message.AgentMessage;
import com.piagent.domain.session.model.message.BranchSummaryMessage;
import com.piagent.domain.session.model.message.CompactionSummaryMessage;
import com.piagent.domain.session.model.message.UserMessage;
import com.piagent.domain.session.model.payload.BranchSummaryPayload;
import com.piagent.domain.session.model.payload.CompactionPayload;
import com.piagent.domain.session.model.payload.CustomMessagePayload;
import com.piagent.domain.session.model.payload.MessagePayload;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.shared.Guard;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 会话上下文投影器：把 entry 日志投影为 LLM 上下文。
 *
 * <p>算法：
 * <ol>
 *   <li>从 leaf 沿 parentId 回溯到根，得到唯一分支；</li>
 *   <li>找到分支上最后一个 COMPACTION；</li>
 *   <li>先输出压缩摘要，再从 firstKeptEntryId 取到分支末尾；</li>
 *   <li>跳过不参与上下文的 entry，校验工具调用配对；</li>
 *   <li>汇总 token 估算。</li>
 * </ol>
 *
 * <p>纯函数：不访问数据库、不调用 LLM；输入相同必得相同输出。
 */
public final class SessionContextProjector {

    /**
     * 投影指定分支。
     *
     * @param entries 候选 entry，可来自同一会话的任意分支；按 ID 索引
     * @param leafId 目标叶子；为 null 时返回空上下文
     * @return 投影结果
     */
    public ProjectedContext project(List<SessionEntry> entries, EntryId leafId) {
        Objects.requireNonNull(entries, "entries 不能为空");
        if (leafId == null) {
            return ProjectedContext.empty();
        }
        Map<EntryId, SessionEntry> byId = index(entries);
        List<SessionEntry> branch = walkToRoot(byId, leafId);
        validateBranch(branch);
        int compactionIndex = findLastCompaction(branch);
        return build(branch, compactionIndex);
    }

    private Map<EntryId, SessionEntry> index(List<SessionEntry> entries) {
        Map<EntryId, SessionEntry> byId = new LinkedHashMap<>();
        for (SessionEntry entry : entries) {
            Objects.requireNonNull(entry, "entry 不能为空");
            Guard.require(byId.putIfAbsent(entry.id(), entry) == null,
                    DomainErrorCode.INVALID_ENTRY, "entry ID 重复: " + entry.id());
        }
        return byId;
    }

    private List<SessionEntry> walkToRoot(Map<EntryId, SessionEntry> byId, EntryId leafId) {
        LinkedList<SessionEntry> branch = new LinkedList<>();
        Set<EntryId> visited = new HashSet<>();
        SessionEntry current = byId.get(leafId);
        Guard.require(current != null, DomainErrorCode.ENTRY_NOT_FOUND, "叶子 entry 不存在: " + leafId);
        while (current != null) {
            Guard.require(visited.add(current.id()), DomainErrorCode.INVALID_BRANCH,
                    "entry 链存在环: " + current.id());
            branch.addFirst(current);
            if (current.parentId() == null) {
                return branch;
            }
            SessionEntry parent = byId.get(current.parentId());
            Guard.require(parent != null, DomainErrorCode.INVALID_BRANCH,
                    "父 entry 不存在: " + current.parentId());
            current = parent;
        }
        return branch;
    }

    private void validateBranch(List<SessionEntry> branch) {
        SessionId sessionId = branch.get(0).sessionId();
        EntryId previousId = null;
        long previousSeq = 0;
        for (SessionEntry entry : branch) {
            Guard.require(entry.sessionId().equals(sessionId), DomainErrorCode.INVALID_BRANCH,
                    "分支包含其他会话的 entry: " + entry.id());
            if (previousId != null) {
                Guard.require(previousId.equals(entry.parentId()), DomainErrorCode.INVALID_BRANCH,
                        "分支父子链断裂: " + entry.id());
                Guard.require(entry.seq() > previousSeq, DomainErrorCode.INVALID_BRANCH,
                        "分支 seq 必须严格递增: " + entry.id());
            }
            previousId = entry.id();
            previousSeq = entry.seq();
        }
    }

    private int findLastCompaction(List<SessionEntry> branch) {
        for (int i = branch.size() - 1; i >= 0; i--) {
            if (branch.get(i).type() == EntryType.COMPACTION) {
                return i;
            }
        }
        return -1;
    }

    private ProjectedContext build(List<SessionEntry> branch, int compactionIndex) {
        List<AgentMessage> messages = new ArrayList<>();
        List<EntryId> trace = new ArrayList<>();
        ToolCallPairing pairing = new ToolCallPairing();
        int totalTokens = 0;
        int start = 0;
        if (compactionIndex >= 0) {
            SessionEntry compaction = branch.get(compactionIndex);
            CompactionPayload payload = (CompactionPayload) compaction.payload();
            start = firstKeptIndex(branch, payload.firstKeptEntryId(), compactionIndex);
            messages.add(new CompactionSummaryMessage(payload.summary(), payload.tokensBefore(), compaction.createdAt()));
            trace.add(compaction.id());
            totalTokens = addTokens(totalTokens, compaction.tokenEstimate());
        }
        for (int i = start; i < branch.size(); i++) {
            SessionEntry entry = branch.get(i);
            AgentMessage message = toMessage(entry);
            if (message == null) {
                continue;
            }
            pairing.accept(message);
            messages.add(message);
            trace.add(entry.id());
            totalTokens = addTokens(totalTokens, entry.tokenEstimate());
        }
        return new ProjectedContext(messages, trace, totalTokens);
    }

    private int firstKeptIndex(List<SessionEntry> branch, EntryId firstKeptEntryId, int compactionIndex) {
        for (int i = 0; i < compactionIndex; i++) {
            if (branch.get(i).id().equals(firstKeptEntryId)) {
                return i;
            }
        }
        throw new DomainException(DomainErrorCode.INVALID_ENTRY,
                "压缩保留起点不在压缩点之前: " + firstKeptEntryId);
    }

    private AgentMessage toMessage(SessionEntry entry) {
        return switch (entry.payload()) {
            case MessagePayload payload -> payload.message();
            case CustomMessagePayload payload -> new UserMessage(payload.content(), entry.createdAt());
            case BranchSummaryPayload payload ->
                    new BranchSummaryMessage(payload.summary(), payload.fromEntryId(), entry.createdAt());
            default -> null;
        };
    }

    private int addTokens(int total, int delta) {
        long sum = (long) total + delta;
        Guard.require(sum <= Integer.MAX_VALUE, DomainErrorCode.INVALID_ENTRY, "token 估算溢出");
        return (int) sum;
    }
}
