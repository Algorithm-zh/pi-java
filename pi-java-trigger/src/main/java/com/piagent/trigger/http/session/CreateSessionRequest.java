package com.piagent.trigger.http.session;

import com.piagent.domain.session.model.ThinkingLevel;

/**
 * 创建会话请求。
 *
 * @param title 标题
 * @param cwd 工作目录（绝对路径）
 * @param modelProfileId 模型档案
 * @param thinkingLevel 思考级别；为空时使用 OFF
 */
public record CreateSessionRequest(String title, String cwd, String modelProfileId,
        ThinkingLevel thinkingLevel) {
}
