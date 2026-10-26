package com.piagent.domain.session.model.payload;

import com.piagent.domain.session.model.EntryType;
import com.piagent.domain.session.model.SessionEntry;

/**
 * entry 载荷。
 *
 * <p>每个实现声明自己对应的 {@link EntryType}；{@link SessionEntry} 构造时强制校验，
 * 防止“消息 entry 里装审批数据”这类脏数据进入日志。
 */
public sealed interface EntryPayload permits
        MessagePayload, CustomMessagePayload, CompactionPayload, BranchSummaryPayload,
        ModelChangePayload, CustomPayload, LabelPayload, SessionInfoPayload,
        ApprovalPayload, RunSummaryPayload, GitCheckpointPayload, GitChangeSetPayload,
        PlanPayload, VerificationPayload {

    /**
     * 返回该载荷对应的 entry 类型。
     *
     * @return entry 类型
     */
    EntryType entryType();
}
