package com.piagent.domain.session.model;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.util.Objects;

/**
 * 会话身份：标识、父子关系、来源与在会话树中的深度。
 *
 * <p>USER 会话是树根（depth=0）；FORK 与 SUBAGENT 都是子会话，
 * 通过 parent/root 串起完整的会话树。身份字段创建后不可变更。
 *
 * <p>父会话被删除时，数据库外键会把 {@code parent_session_id} 置空，
 * 因此子会话可能以“孤儿”形态被重新加载：{@code parentSessionId == null}
 * 但 origin 仍为 FORK/SUBAGENT、depth 仍大于 0。创建路径
 * {@link #child} 始终要求父会话，孤儿态只用于兼容持久化事实。
 *
 * @param id 会话标识
 * @param parentSessionId 父会话；根会话或父会话已删除时为 null
 * @param rootSessionId 根会话
 * @param forkFromEntryId fork/subagent 的起点 entry；SPAWN 子会话为 null
 * @param depth 树深度，用户会话为 0
 * @param origin 来源
 */
public record SessionIdentity(
        SessionId id,
        SessionId parentSessionId,
        SessionId rootSessionId,
        EntryId forkFromEntryId,
        int depth,
        Origin origin) {

    /**
     * 校验会话树身份。
     *
     * @param id 会话标识
     * @param parentSessionId 父会话
     * @param rootSessionId 根会话
     * @param forkFromEntryId fork 起点
     * @param depth 深度
     * @param origin 来源
     */
    public SessionIdentity {
        Objects.requireNonNull(id, "id 不能为空");
        Objects.requireNonNull(rootSessionId, "rootSessionId 不能为空");
        Objects.requireNonNull(origin, "origin 不能为空");
        Guard.require(depth >= 0, DomainErrorCode.INVALID_ARGUMENT, "depth 不能为负数");
        Guard.require(!id.equals(parentSessionId), DomainErrorCode.INVALID_ARGUMENT, "会话不能以自身为父");
        switch (origin) {
            case USER -> {
                Guard.require(depth == 0, DomainErrorCode.INVALID_ARGUMENT, "用户会话深度必须为 0");
                Guard.require(parentSessionId == null, DomainErrorCode.INVALID_ARGUMENT, "用户会话不能有父会话");
                Guard.require(forkFromEntryId == null, DomainErrorCode.INVALID_ARGUMENT, "用户会话不能有 fork 起点");
                Guard.require(rootSessionId.equals(id), DomainErrorCode.INVALID_ARGUMENT, "用户会话必须是根会话");
            }
            case FORK -> {
                Guard.require(depth >= 1, DomainErrorCode.INVALID_ARGUMENT, "fork 会话深度至少为 1");
                Guard.require(forkFromEntryId != null, DomainErrorCode.INVALID_ARGUMENT, "fork 会话必须有起点 entry");
            }
            case SUBAGENT -> Guard.require(depth >= 1, DomainErrorCode.INVALID_ARGUMENT, "子代理会话深度至少为 1");
        }
    }

    /**
     * 创建用户会话身份。
     *
     * @param id 会话标识
     * @return 根身份
     */
    public static SessionIdentity root(SessionId id) {
        Objects.requireNonNull(id, "id 不能为空");
        return new SessionIdentity(id, null, id, null, 0, Origin.USER);
    }

    /**
     * 基于父会话创建子身份。
     *
     * @param id 子会话标识
     * @param parent 父会话身份
     * @param forkFromEntryId fork 起点；SPAWN 为 null
     * @param origin 来源，必须是 FORK 或 SUBAGENT
     * @return 子身份
     */
    public static SessionIdentity child(SessionId id, SessionIdentity parent, EntryId forkFromEntryId, Origin origin) {
        Objects.requireNonNull(parent, "parent 不能为空");
        return new SessionIdentity(id, parent.id(), parent.rootSessionId(), forkFromEntryId, parent.depth() + 1, origin);
    }
}
