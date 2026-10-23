package com.piagent.application.session.command;

import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.ThinkingLevel;

/**
 * 切换会话模型命令。
 *
 * @param sessionId 会话标识
 * @param modelProfileId 目标模型档案
 * @param thinkingLevel 新思考级别；为 null 时沿用当前级别
 */
public record ChangeSessionModelCommand(SessionId sessionId, String modelProfileId,
        ThinkingLevel thinkingLevel) {
}
