package com.piagent.infrastructure.persistence.session;

import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.Origin;
import com.piagent.domain.session.model.SessionConfig;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.SessionIdentity;
import com.piagent.domain.session.model.SessionStatus;
import com.piagent.domain.session.model.ThinkingLevel;
import com.piagent.infrastructure.persistence.codec.SandboxJsonCodec;
import java.nio.file.Path;
import java.time.Instant;

/**
 * `pi_session` 行与 {@link ChatSession} 聚合之间的转换。
 *
 * <p>审计时间列（created_at/updated_at）不属于聚合状态，由仓储在写入时提供。
 */
public final class SessionPoConverter {

    private final SandboxJsonCodec sandboxCodec;

    /**
     * 创建转换器。
     *
     * @param sandboxCodec 沙箱 JSON 编解码
     */
    public SessionPoConverter(SandboxJsonCodec sandboxCodec) {
        this.sandboxCodec = sandboxCodec;
    }

    /**
     * 聚合转持久化对象。
     *
     * @param session 会话
     * @param now 写入时间
     * @return 持久化对象
     */
    public SessionPO toPo(ChatSession session, Instant now) {
        SessionPO po = new SessionPO();
        po.setId(session.identity().id().toString());
        po.setParentSessionId(text(session.identity().parentSessionId()));
        po.setRootSessionId(session.identity().rootSessionId().toString());
        po.setForkFromEntryId(text(session.identity().forkFromEntryId()));
        po.setDepth(session.identity().depth());
        po.setOrigin(session.identity().origin().name());
        po.setTitle(session.title());
        po.setCwd(session.config().cwd().toString());
        po.setModelProfileId(session.config().modelProfileId());
        po.setThinkingLevel(session.config().thinkingLevel().name());
        po.setSandboxJson(sandboxCodec.encode(session.config().sandbox()));
        po.setStatus(session.status().name());
        po.setLeafEntryId(text(session.leafEntryId()));
        po.setVersion(session.version());
        po.setCreatedAt(now);
        po.setUpdatedAt(now);
        return po;
    }

    /**
     * 持久化对象转聚合。
     *
     * @param po 持久化对象
     * @return 会话聚合
     */
    public ChatSession toDomain(SessionPO po) {
        SessionIdentity identity = new SessionIdentity(
                SessionId.parse(po.getId()),
                po.getParentSessionId() == null ? null : SessionId.parse(po.getParentSessionId()),
                SessionId.parse(po.getRootSessionId()),
                po.getForkFromEntryId() == null ? null : EntryId.parse(po.getForkFromEntryId()),
                po.getDepth(),
                Origin.valueOf(po.getOrigin()));
        SessionConfig config = new SessionConfig(Path.of(po.getCwd()), po.getModelProfileId(),
                ThinkingLevel.valueOf(po.getThinkingLevel()), sandboxCodec.decode(po.getSandboxJson()));
        return ChatSession.restore(identity, po.getTitle(), config,
                SessionStatus.valueOf(po.getStatus()),
                po.getLeafEntryId() == null ? null : EntryId.parse(po.getLeafEntryId()),
                po.getVersion());
    }

    private String text(SessionId id) {
        return id == null ? null : id.toString();
    }

    private String text(EntryId id) {
        return id == null ? null : id.toString();
    }
}
