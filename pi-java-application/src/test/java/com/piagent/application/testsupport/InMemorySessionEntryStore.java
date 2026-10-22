package com.piagent.application.testsupport;

import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.port.SessionEntryStore;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 应用层单元测试用的内存 entry 存储。
 */
public final class InMemorySessionEntryStore implements SessionEntryStore {

    private final List<SessionEntry> entries = new ArrayList<>();

    @Override
    public void append(SessionEntry entry) {
        entries.add(entry);
    }

    @Override
    public List<SessionEntry> loadBranch(SessionId sessionId, EntryId leafId) {
        if (leafId == null) {
            return List.of();
        }
        Map<EntryId, SessionEntry> byId = new LinkedHashMap<>();
        for (SessionEntry entry : entries) {
            if (entry.sessionId().equals(sessionId)) {
                byId.put(entry.id(), entry);
            }
        }
        LinkedList<SessionEntry> branch = new LinkedList<>();
        Set<EntryId> visited = new HashSet<>();
        SessionEntry current = byId.get(leafId);
        while (current != null) {
            if (!visited.add(current.id())) {
                throw new DomainException(DomainErrorCode.INVALID_BRANCH, "entry 链存在环");
            }
            branch.addFirst(current);
            current = current.parentId() == null ? null : byId.get(current.parentId());
        }
        if (branch.isEmpty()) {
            throw new DomainException(DomainErrorCode.ENTRY_NOT_FOUND, "叶子 entry 不存在: " + leafId);
        }
        return List.copyOf(branch);
    }

    @Override
    public Optional<SessionEntry> findById(SessionId sessionId, EntryId entryId) {
        return entries.stream()
                .filter(entry -> entry.sessionId().equals(sessionId) && entry.id().equals(entryId))
                .findFirst();
    }

    @Override
    public long countBySession(SessionId sessionId) {
        return entries.stream().filter(entry -> entry.sessionId().equals(sessionId)).count();
    }
}
