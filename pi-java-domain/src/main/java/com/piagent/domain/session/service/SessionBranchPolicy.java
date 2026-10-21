package com.piagent.domain.session.service;

import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.shared.Guard;
import java.util.List;
import java.util.Objects;

/**
 * 分支移动策略：校验目标 entry 是否可以作为新的会话叶子或 fork 起点。
 *
 * <p>核心规则：目标必须属于同一会话，且不能是工具结果。切到工具结果会把
 * toolCall 与 toolResult 拆到两个分支，后续投影必然配对失败。
 */
public final class SessionBranchPolicy {

    /**
     * 校验目标 entry 可作为分支点。
     *
     * @param sessionId 会话标识
     * @param entries 该会话的候选 entry
     * @param target 目标 entry
     * @return 目标 entry
     */
    public SessionEntry requireBranchTarget(SessionId sessionId, List<SessionEntry> entries, EntryId target) {
        Objects.requireNonNull(sessionId, "sessionId 不能为空");
        Objects.requireNonNull(entries, "entries 不能为空");
        Objects.requireNonNull(target, "target 不能为空");
        SessionEntry entry = entries.stream()
                .filter(candidate -> candidate.id().equals(target))
                .findFirst()
                .orElseThrow(() -> new DomainException(DomainErrorCode.ENTRY_NOT_FOUND,
                        "目标 entry 不存在: " + target));
        Guard.require(entry.sessionId().equals(sessionId), DomainErrorCode.INVALID_BRANCH,
                "目标 entry 不属于该会话: " + target);
        Guard.require(!entry.isToolResult(), DomainErrorCode.ENTRY_NOT_BRANCHABLE,
                "不能把分支切换到工具结果 entry: " + target);
        return entry;
    }
}
