package com.piagent.domain.session.model.payload;

import com.piagent.domain.session.model.message.AgentMessage;
import com.piagent.domain.session.model.EntryType;

import java.util.Objects;

/**
 * 普通消息 entry 的载荷。
 *
 * @param message 用户、助手或工具结果消息
 */
public record MessagePayload(AgentMessage message) implements EntryPayload {

    /**
     * 校验消息载荷。
     *
     * @param message 消息
     */
    public MessagePayload {
        Objects.requireNonNull(message, "message 不能为空");
    }

    @Override
    public EntryType entryType() {
        return EntryType.MESSAGE;
    }
}
