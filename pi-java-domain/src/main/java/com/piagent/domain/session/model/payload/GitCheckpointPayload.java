package com.piagent.domain.session.model.payload;

import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.EntryType;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;

/**
 * 一次 run 开始前的工作区检查点。
 *
 * <p>{@code baseLeafId} 记录用户消息之前的叶子：撤销上一轮时把分支退回到这里，
 * 同时把工作区还原到 {@code baseTreeSha}，会话与文件保持一致。</p>
 *
 * @param runId run 标识
 * @param baseLeafId 用户消息之前的叶子；首条消息时为 null
 * @param baseTreeSha 运行开始前的树对象
 * @param headSha 当时 HEAD；空仓库或游离 HEAD 时为 null
 * @param branch 当时分支名；游离 HEAD 或空仓库时为 null
 */
public record GitCheckpointPayload(
        String runId,
        EntryId baseLeafId,
        String baseTreeSha,
        String headSha,
        String branch) implements EntryPayload {

    /**
     * 校验检查点。
     *
     * @param runId run ID
     * @param baseLeafId 基础叶子
     * @param baseTreeSha 树对象
     * @param headSha HEAD
     * @param branch 分支
     */
    public GitCheckpointPayload {
        Guard.notBlank(runId, DomainErrorCode.INVALID_ARGUMENT, "runId 不能为空");
        Guard.notBlank(baseTreeSha, DomainErrorCode.INVALID_ARGUMENT, "baseTreeSha 不能为空");
    }

    @Override
    public EntryType entryType() {
        return EntryType.GIT_CHECKPOINT;
    }
}
