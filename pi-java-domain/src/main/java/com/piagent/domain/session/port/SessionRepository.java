package com.piagent.domain.session.port;

import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.SessionId;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 会话聚合仓储。
 *
 * <p>只管理会话元数据；entry 由 {@link SessionEntryStore} 追加。
 * 更新使用 {@code session.version()} 做乐观锁，影响行数为 0 时必须抛出
 * {@code CONCURRENT_MODIFICATION}。
 */
public interface SessionRepository {

    /**
     * 按标识加载会话。
     *
     * @param id 会话标识
     * @return 会话，不存在时为空
     */
    Optional<ChatSession> findById(SessionId id);

    /**
     * 插入新会话。
     *
     * @param session 会话
     */
    void insert(ChatSession session);

    /**
     * 以乐观锁更新会话。
     *
     * @param session 会话；其 version 作为 CAS 期望值
     */
    void update(ChatSession session);

    /**
     * 按工作目录查询最近会话。
     *
     * @param cwd 工作目录
     * @param limit 最大条数
     * @return 会话列表，按更新时间倒序
     */
    List<ChatSession> findByCwd(Path cwd, int limit);

    /**
     * 查询直接子会话。
     *
     * @param parentId 父会话标识
     * @return 子会话列表
     */
    List<ChatSession> findChildren(SessionId parentId);

    /**
     * 查询最近更新的会话，供会话侧栏使用。
     *
     * @param limit 最大条数
     * @return 会话列表，按更新时间倒序
     */
    List<ChatSession> findRecent(int limit);

    /**
     * 查询卡在运行态的会话，供启动时自愈。
     *
     * @param limit 最大条数
     * @return 状态为 RUNNING 或 WAITING_APPROVAL 的会话
     */
    List<ChatSession> findStuck(int limit);

    /**
     * 删除会话及其 entry、审批记录。
     *
     * <p>外键在数据库层级联删除；调用方需先确认会话已停止。</p>
     *
     * @param id 会话标识
     */
    void delete(SessionId id);
}
