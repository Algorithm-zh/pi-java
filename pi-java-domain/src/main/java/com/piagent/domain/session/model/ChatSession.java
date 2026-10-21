package com.piagent.domain.session.model;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.util.Objects;

/**
 * 会话聚合根：只保存元数据与当前分支叶子，不持有 entry 列表。
 *
 * <p>entry 日志由 {@code SessionEntryStore} 单独追加，聚合根不重复保存历史；
 * 这样分支移动只改 {@code leafEntryId}，避免把整棵会话树读进内存。
 *
 * <p>乐观锁版本 {@code version} 由仓储在加载时填充，update 时作为 CAS 条件。
 * 新建会话版本为 1；仓储成功更新后版本加 1，调用方若继续修改应先同步版本。
 *
 * <p>本类型不可变：所有状态变更返回新实例，避免同一对象被并发修改。
 *
 * @param identity 会话身份
 * @param title 会话标题
 * @param config 执行配置
 * @param status 会话状态
 * @param leafEntryId 当前分支叶子；空会话为 null
 * @param version 乐观锁版本
 */
public record ChatSession(
        SessionIdentity identity,
        String title,
        SessionConfig config,
        SessionStatus status,
        EntryId leafEntryId,
        long version) {

    /** 新建会话的初始版本。 */
    public static final long INITIAL_VERSION = 1L;

    /** 标题最大长度。 */
    public static final int MAX_TITLE_LENGTH = 255;

    /**
     * 校验会话状态。
     *
     * @param identity 身份
     * @param title 标题
     * @param config 配置
     * @param status 状态
     * @param leafEntryId 叶子
     * @param version 版本
     */
    public ChatSession {
        Objects.requireNonNull(identity, "identity 不能为空");
        title = Guard.notBlank(title, DomainErrorCode.INVALID_ARGUMENT, "会话标题不能为空");
        Guard.maxLength(title, MAX_TITLE_LENGTH, DomainErrorCode.INVALID_ARGUMENT,
                "会话标题不能超过 " + MAX_TITLE_LENGTH + " 个字符");
        Objects.requireNonNull(config, "config 不能为空");
        Objects.requireNonNull(status, "status 不能为空");
        Guard.require(version >= 0, DomainErrorCode.INVALID_ARGUMENT, "version 不能为负数");
    }

    /**
     * 创建用户会话。
     *
     * @param id 会话标识
     * @param title 标题
     * @param config 执行配置
     * @return 空闲状态的新会话
     */
    public static ChatSession createRoot(SessionId id, String title, SessionConfig config) {
        return new ChatSession(SessionIdentity.root(id), title, config, SessionStatus.IDLE, null, INITIAL_VERSION);
    }

    /**
     * 基于父会话创建分支或子代理会话。
     *
     * @param id 子会话标识
     * @param parent 父会话
     * @param forkFromEntryId fork 起点；SPAWN 子会话为 null
     * @param origin 来源，必须是 FORK 或 SUBAGENT
     * @param title 子会话标题
     * @param config 子会话配置，沙箱应取父会话的子集
     * @return 空闲状态的子会话
     */
    public static ChatSession createChild(SessionId id, ChatSession parent, EntryId forkFromEntryId,
                                          Origin origin, String title, SessionConfig config) {
        Objects.requireNonNull(parent, "parent 不能为空");
        Guard.require(parent.status != SessionStatus.ARCHIVED, DomainErrorCode.SESSION_ARCHIVED,
                "不能从已归档会话创建子会话");
        Guard.require(origin == Origin.FORK || origin == Origin.SUBAGENT, DomainErrorCode.INVALID_ARGUMENT,
                "子会话来源必须是 FORK 或 SUBAGENT");
        SessionIdentity child = SessionIdentity.child(id, parent.identity, forkFromEntryId, origin);
        return new ChatSession(child, title, config, SessionStatus.IDLE, null, INITIAL_VERSION);
    }

    /**
     * 从持久化数据重建聚合。仅供仓储适配器使用。
     *
     * @param identity 身份
     * @param title 标题
     * @param config 配置
     * @param status 状态
     * @param leafEntryId 叶子
     * @param version 版本
     * @return 重建的会话
     */
    public static ChatSession restore(SessionIdentity identity, String title, SessionConfig config,
                                      SessionStatus status, EntryId leafEntryId, long version) {
        return new ChatSession(identity, title, config, status, leafEntryId, version);
    }

    /**
     * 重命名会话。
     *
     * @param newTitle 新标题
     * @return 新会话实例
     */
    public ChatSession rename(String newTitle) {
        return new ChatSession(identity, newTitle, config, status, leafEntryId, version);
    }

    /**
     * 切换模型与思考级别。只允许空闲会话切换，避免运行中模型漂移。
     *
     * @param modelProfileId 新模型档案
     * @param thinkingLevel 新思考级别
     * @return 新会话实例
     */
    public ChatSession changeModel(String modelProfileId, ThinkingLevel thinkingLevel) {
        Guard.require(status == SessionStatus.IDLE, DomainErrorCode.INVALID_STATE_TRANSITION,
                "只有空闲会话可以切换模型，当前状态: " + status);
        return new ChatSession(identity, title, config.withModel(modelProfileId, thinkingLevel),
                status, leafEntryId, version);
    }

    /**
     * 移动当前分支叶子。
     *
     * <p>只做状态与空值校验；目标 entry 是否属于本会话、是否为 toolResult
     * 由 {@code SessionBranchPolicy} 结合 entry 日志判断。
     *
     * @param target 新叶子
     * @return 新会话实例
     */
    public ChatSession moveLeaf(EntryId target) {
        Objects.requireNonNull(target, "target 不能为空");
        Guard.require(status != SessionStatus.ARCHIVED, DomainErrorCode.SESSION_ARCHIVED,
                "已归档会话不能移动分支");
        Guard.require(status == SessionStatus.IDLE, DomainErrorCode.INVALID_STATE_TRANSITION,
                "只有空闲会话可以移动分支，当前状态: " + status);
        Guard.require(!target.equals(leafEntryId), DomainErrorCode.INVALID_BRANCH, "目标已经是当前叶子");
        return new ChatSession(identity, title, config, status, target, version);
    }

    /**
     * 标记会话开始运行。
     *
     * @return 新会话实例
     */
    public ChatSession markRunning() {
        Guard.require(status == SessionStatus.IDLE || status == SessionStatus.WAITING_APPROVAL,
                DomainErrorCode.INVALID_STATE_TRANSITION, "会话不能从 " + status + " 进入 RUNNING");
        return withStatus(SessionStatus.RUNNING);
    }

    /**
     * 标记会话正在等待审批。
     *
     * @return 新会话实例
     */
    public ChatSession markWaitingApproval() {
        Guard.require(status == SessionStatus.RUNNING, DomainErrorCode.INVALID_STATE_TRANSITION,
                "会话不能从 " + status + " 进入 WAITING_APPROVAL");
        return withStatus(SessionStatus.WAITING_APPROVAL);
    }

    /**
     * 标记会话回到空闲。
     *
     * @return 新会话实例
     */
    public ChatSession markIdle() {
        Guard.require(status == SessionStatus.RUNNING || status == SessionStatus.WAITING_APPROVAL,
                DomainErrorCode.INVALID_STATE_TRANSITION, "会话不能从 " + status + " 回到 IDLE");
        return withStatus(SessionStatus.IDLE);
    }

    /**
     * 归档会话。归档后不再允许运行或移动分支。
     *
     * @return 新会话实例
     */
    public ChatSession archive() {
        Guard.require(status == SessionStatus.IDLE, DomainErrorCode.INVALID_STATE_TRANSITION,
                "只有空闲会话可以归档，当前状态: " + status);
        return withStatus(SessionStatus.ARCHIVED);
    }

    /**
     * 同步持久化版本。仅供仓储更新成功后调用。
     *
     * @param newVersion 新版本
     * @return 新会话实例
     */
    public ChatSession withVersion(long newVersion) {
        Guard.require(newVersion >= version, DomainErrorCode.INVALID_ARGUMENT, "版本不能回退");
        return new ChatSession(identity, title, config, status, leafEntryId, newVersion);
    }

    /**
     * 是否为空会话（没有 entry）。
     *
     * @return true 表示尚无 entry
     */
    public boolean isEmpty() {
        return leafEntryId == null;
    }

    private ChatSession withStatus(SessionStatus newStatus) {
        return new ChatSession(identity, title, config, newStatus, leafEntryId, version);
    }
}
