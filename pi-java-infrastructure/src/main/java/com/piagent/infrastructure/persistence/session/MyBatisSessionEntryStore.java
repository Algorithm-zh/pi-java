package com.piagent.infrastructure.persistence.session;

import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.port.SessionEntryStore;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Repository;

/**
 * 基于 MyBatis 的会话 entry 存储。
 *
 * <p>读取时一次性加载会话 entry 并按 parentId 回溯目标分支；
 * v0.1 单会话规模有限，先保证正确性，后续可替换为递归 CTE。
 */
@Repository
public class MyBatisSessionEntryStore implements SessionEntryStore {

    private final SessionEntryMapper mapper;
    private final SessionEntryPoConverter converter;

    /**
     * 创建存储。
     *
     * @param mapper MyBatis 映射器
     * @param converter 行转换器
     */
    public MyBatisSessionEntryStore(SessionEntryMapper mapper, SessionEntryPoConverter converter) {
        this.mapper = mapper;
        this.converter = converter;
    }

    @Override
    public void append(SessionEntry entry) {
        mapper.insert(converter.toPo(entry));
    }

    @Override
    public List<SessionEntry> loadBranch(SessionId sessionId, EntryId leafId) {
        if (leafId == null) {
            return List.of();
        }
        Map<EntryId, SessionEntry> byId = new LinkedHashMap<>();
        for (SessionEntryPO po : mapper.findAllBySession(sessionId.toString())) {
            SessionEntry entry = converter.toDomain(po);
            byId.put(entry.id(), entry);
        }
        SessionEntry current = byId.get(leafId);
        if (current == null) {
            throw new DomainException(DomainErrorCode.ENTRY_NOT_FOUND, "叶子 entry 不存在: " + leafId);
        }
        LinkedList<SessionEntry> branch = new LinkedList<>();
        Set<EntryId> visited = new HashSet<>();
        while (current != null) {
            if (!visited.add(current.id())) {
                throw new DomainException(DomainErrorCode.INVALID_BRANCH, "entry 链存在环: " + current.id());
            }
            branch.addFirst(current);
            if (current.parentId() == null) {
                break;
            }
            current = byId.get(current.parentId());
            if (current == null) {
                throw new DomainException(DomainErrorCode.INVALID_BRANCH,
                        "父 entry 不存在: " + branch.getFirst().parentId());
            }
        }
        return List.copyOf(branch);
    }

    @Override
    public Optional<SessionEntry> findById(SessionId sessionId, EntryId entryId) {
        SessionEntryPO po = mapper.findById(sessionId.toString(), entryId.toString());
        return Optional.ofNullable(po).map(converter::toDomain);
    }

    @Override
    public long countBySession(SessionId sessionId) {
        return mapper.countBySession(sessionId.toString());
    }
}
