package com.piagent.domain.session.model.message;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.time.Instant;
import java.util.Objects;

/**
 * 由 COMPACTION entry 投影出的历史摘要消息。
 *
 * <p>它不是持久化消息；每次投影都从压缩 entry 重新生成。
 *
 * @param summary 结构化摘要
 * @param tokensBefore 压缩前估算 token
 * @param timestamp 压缩时间
 */
public record CompactionSummaryMessage(String summary, int tokensBefore, Instant timestamp)
        implements AgentMessage {

    /**
     * 校验摘要消息。
     *
     * @param summary 摘要
     * @param tokensBefore 压缩前 token
     * @param timestamp 时间
     */
    public CompactionSummaryMessage {
        Guard.notBlank(summary, DomainErrorCode.INVALID_ARGUMENT, "摘要不能为空");
        Guard.require(tokensBefore >= 0, DomainErrorCode.INVALID_ARGUMENT, "tokensBefore 不能为负数");
        Objects.requireNonNull(timestamp, "timestamp 不能为空");
    }
}
