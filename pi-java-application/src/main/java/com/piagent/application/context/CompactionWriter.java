package com.piagent.application.context;

import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.payload.CompactionPayload;

/**
 * COMPACTION entry 的落库回调。
 *
 * <p>压缩编排不直接依赖会话仓储：run 内压缩与会话空闲时的手动压缩在叶子推进方式上
 * 不同，由回调实现方决定。回调必须保证“追加 entry + 推进叶子”在同一个短事务里完成。</p>
 */
@FunctionalInterface
public interface CompactionWriter {

    /**
     * 追加压缩 entry。
     *
     * @param sessionId 会话标识
     * @param payload 压缩载荷
     * @return 新 entry
     */
    SessionEntry append(SessionId sessionId, CompactionPayload payload);
}
