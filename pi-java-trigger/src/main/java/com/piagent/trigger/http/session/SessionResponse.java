package com.piagent.trigger.http.session;

import com.piagent.domain.session.model.ChatSession;

/**
 * 会话元数据响应。
 *
 * @param id 会话 ID
 * @param parentSessionId 父会话 ID
 * @param rootSessionId 根会话 ID
 * @param title 标题
 * @param cwd 工作目录
 * @param modelProfileId 模型档案
 * @param thinkingLevel 思考级别
 * @param status 会话状态
 * @param leafEntryId 当前叶子
 * @param depth 树深度
 * @param origin 来源
 * @param version 乐观锁版本
 */
public record SessionResponse(String id, String parentSessionId, String rootSessionId,
        String title, String cwd, String modelProfileId, String thinkingLevel, String status,
        String leafEntryId, int depth, String origin, long version) {

    /**
     * 从聚合转换。
     *
     * @param session 会话
     * @return 响应
     */
    public static SessionResponse from(ChatSession session) {
        return new SessionResponse(session.identity().id().toString(),
                session.identity().parentSessionId() == null ? null
                        : session.identity().parentSessionId().toString(),
                session.identity().rootSessionId().toString(), session.title(),
                session.config().cwd().toString(), session.config().modelProfileId(),
                session.config().thinkingLevel().name(), session.status().name(),
                session.leafEntryId() == null ? null : session.leafEntryId().toString(),
                session.identity().depth(), session.identity().origin().name(), session.version());
    }
}
