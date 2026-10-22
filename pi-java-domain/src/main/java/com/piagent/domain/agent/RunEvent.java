package com.piagent.domain.agent;

import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 一次 run 中的有序运行事件。
 *
 * <p>{@code seq} 从 1 开始单调递增，是 SSE {@code Last-Event-ID} 的重放依据。
 * {@code data} 只允许 JSON 可序列化的 JDK 值；事件由应用层构造，基础设施与
 * 触发层只负责传输，不在事件里放领域对象，避免把序列化规则带进领域。
 *
 * @param seq run 内序号，从 1 开始
 * @param runId 运行标识
 * @param sessionId 会话标识
 * @param type 事件类型
 * @param at 发生时间
 * @param data 事件数据
 */
public record RunEvent(long seq, RunId runId, SessionId sessionId, RunEventType type,
        Instant at, Map<String, Object> data) {

    /**
     * 校验事件并冻结数据。
     *
     * @param seq 序号
     * @param runId 运行标识
     * @param sessionId 会话标识
     * @param type 事件类型
     * @param at 时间
     * @param data 数据
     */
    public RunEvent {
        Guard.require(seq >= 0, DomainErrorCode.INVALID_ARGUMENT, "事件 seq 不能为负数");
        Objects.requireNonNull(runId, "runId 不能为空");
        Objects.requireNonNull(sessionId, "sessionId 不能为空");
        Objects.requireNonNull(type, "type 不能为空");
        Objects.requireNonNull(at, "at 不能为空");
        Objects.requireNonNull(data, "data 不能为空");
        data = Collections.unmodifiableMap(new LinkedHashMap<>(data));
    }
}
