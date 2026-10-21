package com.piagent.application.session.service;

import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.port.SessionRepository;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 应用层单元测试用的内存会话仓储，模拟 version 乐观锁语义。
 */
final class InMemorySessionRepository implements SessionRepository {

    private final Map<SessionId, ChatSession> sessions = new LinkedHashMap<>();

    @Override
    public Optional<ChatSession> findById(SessionId id) {
        return Optional.ofNullable(sessions.get(id));
    }

    @Override
    public void insert(ChatSession session) {
        SessionId id = session.identity().id();
        if (sessions.putIfAbsent(id, session) != null) {
            throw new IllegalStateException("重复插入会话: " + id);
        }
    }

    @Override
    public void update(ChatSession session) {
        SessionId id = session.identity().id();
        ChatSession current = sessions.get(id);
        if (current == null || current.version() != session.version()) {
            throw new DomainException(DomainErrorCode.CONCURRENT_MODIFICATION, "版本冲突: " + id);
        }
        sessions.put(id, session.withVersion(session.version() + 1));
    }

    @Override
    public List<ChatSession> findByCwd(Path cwd, int limit) {
        Path normalized = cwd.toAbsolutePath().normalize();
        return sessions.values().stream()
                .filter(session -> session.config().cwd().equals(normalized))
                .limit(limit)
                .toList();
    }

    @Override
    public List<ChatSession> findChildren(SessionId parentId) {
        return sessions.values().stream()
                .filter(session -> parentId.equals(session.identity().parentSessionId()))
                .toList();
    }
}
