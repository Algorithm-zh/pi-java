package com.piagent.domain.session.model;

import com.piagent.domain.session.model.message.ToolResultMessage;
import com.piagent.domain.session.model.payload.EntryPayload;
import com.piagent.domain.session.model.payload.MessagePayload;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.time.Instant;
import java.util.Objects;

/**
 * 会话日志中的一条不可变 entry，是会话历史的唯一事实源。
 *
 * <p>关键不变量：
 * <ul>
 *   <li>{@code payload.entryType()} 必须等于 {@code type}；</li>
 *   <li>根 entry 的 {@code parentId} 为 null 且 {@code seq = 1}；</li>
 *   <li>非根 entry 必须指向同一会话内的父 entry；</li>
 *   <li>payload 版本从 1 开始，只增字段不改语义。</li>
 * </ul>
 *
 * @param id entry 标识
 * @param sessionId 所属会话
 * @param seq 会话内单调序号，从 1 开始
 * @param parentId 树形父节点；根为 null
 * @param type entry 类型
 * @param payloadVersion 载荷模式版本
 * @param payload 载荷
 * @param tokenEstimate 估算 token
 * @param createdAt 创建时间
 */
public record SessionEntry(
        EntryId id,
        SessionId sessionId,
        long seq,
        EntryId parentId,
        EntryType type,
        int payloadVersion,
        EntryPayload payload,
        int tokenEstimate,
        Instant createdAt) {

    /** 当前载荷模式版本；版本 2 起消息可包含图片块。 */
    public static final int CURRENT_PAYLOAD_VERSION = 2;

    /**
     * 校验 entry 结构。
     *
     * @param id entry 标识
     * @param sessionId 所属会话
     * @param seq 序号
     * @param parentId 父节点
     * @param type 类型
     * @param payloadVersion 载荷版本
     * @param payload 载荷
     * @param tokenEstimate token 估算
     * @param createdAt 创建时间
     */
    public SessionEntry {
        Objects.requireNonNull(id, "id 不能为空");
        Objects.requireNonNull(sessionId, "sessionId 不能为空");
        Guard.require(seq >= 1, DomainErrorCode.INVALID_ENTRY, "seq 必须从 1 开始");
        Objects.requireNonNull(type, "type 不能为空");
        Guard.require(payloadVersion >= 1, DomainErrorCode.UNSUPPORTED_PAYLOAD, "payloadVersion 必须大于等于 1");
        Objects.requireNonNull(payload, "payload 不能为空");
        Guard.require(tokenEstimate >= 0, DomainErrorCode.INVALID_ENTRY, "tokenEstimate 不能为负数");
        Objects.requireNonNull(createdAt, "createdAt 不能为空");
        Guard.require(payload.entryType() == type, DomainErrorCode.ENTRY_TYPE_MISMATCH,
                "entry 类型 " + type + " 与载荷类型 " + payload.entryType() + " 不匹配");
        if (seq == 1) {
            Guard.require(parentId == null, DomainErrorCode.INVALID_ENTRY, "根 entry 不能有父节点");
        } else {
            Guard.require(parentId != null, DomainErrorCode.INVALID_ENTRY, "非根 entry 必须有父节点");
        }
    }

    /**
     * 是否为承载消息的 entry。
     *
     * @return true 表示 MESSAGE 或 CUSTOM_MESSAGE
     */
    public boolean isMessage() {
        return type == EntryType.MESSAGE || type == EntryType.CUSTOM_MESSAGE;
    }


    /**
     * 是否为工具结果 entry。
     *
     * @return true 表示载荷是工具结果消息
     */
    public boolean isToolResult() {
        return payload instanceof MessagePayload messagePayload
                && messagePayload.message() instanceof ToolResultMessage;
    }
}
