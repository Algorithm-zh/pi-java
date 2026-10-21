package com.piagent.infrastructure.persistence.session;

import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.EntryType;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionId;
import com.piagent.infrastructure.persistence.codec.EntryPayloadCodec;

/**
 * `pi_session_entry` 行与 {@link SessionEntry} 之间的转换。
 */
public final class SessionEntryPoConverter {

    private final EntryPayloadCodec payloadCodec;

    /**
     * 创建转换器。
     *
     * @param payloadCodec 载荷编解码
     */
    public SessionEntryPoConverter(EntryPayloadCodec payloadCodec) {
        this.payloadCodec = payloadCodec;
    }

    /**
     * entry 转持久化对象。
     *
     * @param entry entry
     * @return 持久化对象
     */
    public SessionEntryPO toPo(SessionEntry entry) {
        SessionEntryPO po = new SessionEntryPO();
        po.setId(entry.id().toString());
        po.setSessionId(entry.sessionId().toString());
        po.setSeq(entry.seq());
        po.setParentId(entry.parentId() == null ? null : entry.parentId().toString());
        po.setEntryType(entry.type().name());
        po.setPayloadVersion(entry.payloadVersion());
        po.setPayloadJson(payloadCodec.encode(entry.payload()));
        po.setTokenEstimate(entry.tokenEstimate());
        po.setCreatedAt(entry.createdAt());
        return po;
    }

    /**
     * 持久化对象转 entry。
     *
     * @param po 持久化对象
     * @return entry
     */
    public SessionEntry toDomain(SessionEntryPO po) {
        EntryType type = EntryType.valueOf(po.getEntryType());
        return new SessionEntry(EntryId.parse(po.getId()), SessionId.parse(po.getSessionId()),
                po.getSeq(), po.getParentId() == null ? null : EntryId.parse(po.getParentId()),
                type, po.getPayloadVersion(),
                payloadCodec.decode(type, po.getPayloadVersion(), po.getPayloadJson()),
                po.getTokenEstimate(), po.getCreatedAt());
    }
}
