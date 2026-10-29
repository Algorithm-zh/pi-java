package com.piagent.infrastructure.persistence.session;

import com.piagent.application.identity.AuthContext;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.port.SessionRepository;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 基于 MyBatis 的会话聚合仓储。
 *
 * <p>更新使用 version 乐观锁；影响行数为 0 时统一按并发冲突处理，
 * 由应用层决定重试或反馈给用户。所有查询与写入都追加当前租户条件，
 * 跨租户访问表现为“会话不存在”。</p>
 */
@Repository
public class MyBatisSessionRepository implements SessionRepository {

    private final SessionMapper mapper;
    private final SessionPoConverter converter;
    private final Clock clock;

    /**
     * 创建仓储。
     *
     * @param mapper MyBatis 映射器
     * @param converter 行转换器
     * @param clock 时钟
     */
    public MyBatisSessionRepository(SessionMapper mapper, SessionPoConverter converter, Clock clock) {
        this.mapper = mapper;
        this.converter = converter;
        this.clock = clock;
    }

    @Override
    public Optional<ChatSession> findById(SessionId id) {
        return Optional.ofNullable(mapper.findById(id.toString(), tenantId()))
                .map(converter::toDomain);
    }

    @Override
    public void insert(ChatSession session) {
        mapper.insert(toPo(session));
    }

    @Override
    public void update(ChatSession session) {
        int rows = mapper.update(toPo(session));
        if (rows == 0) {
            throw new DomainException(DomainErrorCode.CONCURRENT_MODIFICATION,
                    "会话已被其他写入修改，请重新加载: " + session.identity().id());
        }
    }

    @Override
    public List<ChatSession> findByCwd(Path cwd, int limit) {
        if (limit <= 0) {
            throw new DomainException(DomainErrorCode.INVALID_ARGUMENT, "limit 必须大于 0");
        }
        Path normalized = cwd.toAbsolutePath().normalize();
        return mapper.findByCwd(normalized.toString(), tenantId(), limit).stream()
                .map(converter::toDomain)
                .toList();
    }

    @Override
    public List<ChatSession> findChildren(SessionId parentId) {
        return mapper.findChildren(parentId.toString(), tenantId()).stream()
                .map(converter::toDomain)
                .toList();
    }

    @Override
    public List<ChatSession> findRecent(int limit) {
        if (limit <= 0) {
            throw new DomainException(DomainErrorCode.INVALID_ARGUMENT, "limit 必须大于 0");
        }
        return mapper.findRecent(tenantId(), limit).stream()
                .map(converter::toDomain)
                .toList();
    }

    @Override
    public List<ChatSession> findStuck(int limit) {
        if (limit <= 0) {
            throw new DomainException(DomainErrorCode.INVALID_ARGUMENT, "limit 必须大于 0");
        }
        return mapper.findStuck(tenantId(), limit).stream()
                .map(converter::toDomain)
                .toList();
    }

    @Override
    public void delete(SessionId id) {
        mapper.delete(id.toString(), tenantId());
    }

    private SessionPO toPo(ChatSession session) {
        SessionPO po = converter.toPo(session, clock.instant());
        po.setTenantId(tenantId());
        return po;
    }

    private String tenantId() {
        return AuthContext.tenantId().toString();
    }
}
