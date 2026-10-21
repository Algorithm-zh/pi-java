package com.piagent.infrastructure.persistence.session;

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
 * 由应用层决定重试或反馈给用户。
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
        return Optional.ofNullable(mapper.findById(id.toString())).map(converter::toDomain);
    }

    @Override
    public void insert(ChatSession session) {
        mapper.insert(converter.toPo(session, clock.instant()));
    }

    @Override
    public void update(ChatSession session) {
        int rows = mapper.update(converter.toPo(session, clock.instant()));
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
        return mapper.findByCwd(normalized.toString(), limit).stream()
                .map(converter::toDomain)
                .toList();
    }

    @Override
    public List<ChatSession> findChildren(SessionId parentId) {
        return mapper.findChildren(parentId.toString()).stream()
                .map(converter::toDomain)
                .toList();
    }
}
