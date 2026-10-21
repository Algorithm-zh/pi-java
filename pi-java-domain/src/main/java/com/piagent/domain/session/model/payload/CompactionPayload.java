package com.piagent.domain.session.model.payload;

import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.EntryType;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.util.Map;
import java.util.Objects;

/**
 * 压缩摘要载荷。
 *
 * @param summary 结构化摘要
 * @param firstKeptEntryId 压缩后保留的第一条 entry
 * @param tokensBefore 压缩前估算 token
 * @param details 扩展细节（读写文件列表等）
 */
public record CompactionPayload(
        String summary,
        EntryId firstKeptEntryId,
        int tokensBefore,
        Map<String, Object> details) implements EntryPayload {

    /**
     * 校验压缩载荷。
     *
     * @param summary 摘要
     * @param firstKeptEntryId 保留起点
     * @param tokensBefore 压缩前 token
     * @param details 细节
     */
    public CompactionPayload {
        Guard.notBlank(summary, DomainErrorCode.INVALID_ARGUMENT, "压缩摘要不能为空");
        Objects.requireNonNull(firstKeptEntryId, "firstKeptEntryId 不能为空");
        Guard.require(tokensBefore >= 0, DomainErrorCode.INVALID_ARGUMENT, "tokensBefore 不能为负数");
        Objects.requireNonNull(details, "details 不能为空");
        details = Map.copyOf(details);
    }

    @Override
    public EntryType entryType() {
        return EntryType.COMPACTION;
    }
}
