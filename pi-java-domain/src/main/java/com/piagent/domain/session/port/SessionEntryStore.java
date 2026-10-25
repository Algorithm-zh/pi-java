package com.piagent.domain.session.port;

import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionId;
import java.util.List;
import java.util.Optional;

/**
 * 会话 entry 追加与读取端口。
 *
 * <p>entry 只追加、不更新；唯一的删除场景是撤销会话的第一轮，此时整条日志
 * 清空后重新从根开始。{@code (session_id, seq)} 唯一，同一会话的并发追加
 * 由应用层单写者锁与数据库唯一键共同保证。
 */
public interface SessionEntryStore {

    /**
     * 追加一条 entry。
     *
     * <p>必须与 {@link SessionRepository#update} 处于同一短事务，
     * 先插入 entry，再以 CAS 更新 leaf 与 version。
     *
     * @param entry entry
     */
    void append(SessionEntry entry);

    /**
     * 从根到指定叶子加载分支。
     *
     * @param sessionId 会话标识
     * @param leafId 叶子；为 null 时返回空列表
     * @return 按 seq 升序排列的分支
     */
    List<SessionEntry> loadBranch(SessionId sessionId, EntryId leafId);

    /**
     * 查询单条 entry。
     *
     * @param sessionId 会话标识
     * @param entryId entry 标识
     * @return entry，不存在时为空
     */
    Optional<SessionEntry> findById(SessionId sessionId, EntryId entryId);

    /**
     * 统计会话 entry 数量。
     *
     * <p>没有删减时数量等于当前最大 seq；应用层用它分配下一个 seq。
     *
     * @param sessionId 会话标识
     * @return entry 数量
     */
    long countBySession(SessionId sessionId);

    /**
     * 删除会话的全部 entry。
     *
     * <p>只用于撤销会话第一轮：分支回到空会话后，下一次追加必须是新的根
     * （seq = 1、parent = null），因此旧日志不能再保留。</p>
     *
     * @param sessionId 会话标识
     */
    void deleteAll(SessionId sessionId);
}
