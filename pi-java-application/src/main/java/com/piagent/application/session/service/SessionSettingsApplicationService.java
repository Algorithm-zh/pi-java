package com.piagent.application.session.service;

import com.piagent.application.session.command.ChangeSessionModelCommand;
import com.piagent.application.session.command.RenameSessionCommand;
import com.piagent.domain.model.ModelId;
import com.piagent.domain.model.ModelProfile;
import com.piagent.domain.model.port.ModelProfileRepository;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.ThinkingLevel;
import com.piagent.domain.session.port.SessionRepository;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.shared.Guard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 会话设置用例：重命名、切换模型与归档。
 *
 * <p>与 {@link SessionApplicationService} 分开，避免核心消息用例依赖模型档案仓储；
 * 当前分支的 entry 日志不受这些操作影响，因此事务都只更新会话元数据一行。</p>
 */
@Service
public class SessionSettingsApplicationService {

    private final SessionRepository sessions;
    private final ModelProfileRepository models;

    /**
     * 创建设置用例服务。
     *
     * @param sessions 会话仓储
     * @param models 模型档案仓储
     */
    public SessionSettingsApplicationService(SessionRepository sessions,
            ModelProfileRepository models) {
        this.sessions = sessions;
        this.models = models;
    }

    /**
     * 重命名会话。归档会话仍允许改名，方便在侧栏里辨认。
     *
     * @param command 重命名命令
     * @return 更新后的会话
     */
    @Transactional
    public ChatSession rename(RenameSessionCommand command) {
        ChatSession current = require(command.sessionId());
        return save(current.rename(command.title()));
    }

    /**
     * 切换会话使用的模型与思考级别。
     *
     * <p>目标档案必须存在且启用；切换只影响之后的 run，历史 assistant 消息
     * 仍保留各自实际使用的模型名。运行中的会话由领域层拒绝切换。</p>
     *
     * @param command 切换命令
     * @return 更新后的会话
     */
    @Transactional
    public ChatSession changeModel(ChangeSessionModelCommand command) {
        ChatSession current = require(command.sessionId());
        ModelProfile profile = models.findById(new ModelId(command.modelProfileId()))
                .orElseThrow(() -> new DomainException(DomainErrorCode.MODEL_NOT_FOUND,
                        "模型档案不存在: " + command.modelProfileId()));
        Guard.require(profile.enabled(), DomainErrorCode.MODEL_DISABLED,
                "模型档案已停用: " + command.modelProfileId());
        ThinkingLevel level = command.thinkingLevel() == null
                ? current.config().thinkingLevel() : command.thinkingLevel();
        return save(current.changeModel(command.modelProfileId(), level));
    }

    /**
     * 归档会话。只有空闲会话可以归档，归档后不再接受新消息。
     *
     * @param sessionId 会话标识
     * @return 更新后的会话
     */
    @Transactional
    public ChatSession archive(SessionId sessionId) {
        return save(require(sessionId).archive());
    }

    private ChatSession save(ChatSession session) {
        sessions.update(session);
        return session.withVersion(session.version() + 1);
    }

    private ChatSession require(SessionId sessionId) {
        return sessions.findById(sessionId)
                .orElseThrow(() -> new DomainException(DomainErrorCode.SESSION_NOT_FOUND,
                        "会话不存在: " + sessionId));
    }
}
