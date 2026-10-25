package com.piagent.application.session.service;

import com.piagent.application.session.command.ChangeSessionModelCommand;
import com.piagent.application.session.command.RenameSessionCommand;
import com.piagent.domain.model.ModelId;
import com.piagent.domain.model.ModelProfile;
import com.piagent.domain.model.port.ModelProfileRepository;
import com.piagent.domain.model.service.ModelSelectionPolicy;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.SessionStatus;
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
 * <p>会话管理操作保持独立事务；当前分支的 entry 日志不受这些操作影响，
 * 因此事务都只更新会话元数据一行。</p>
 */
@Service
public class SessionSettingsApplicationService {

    private final SessionRepository sessions;
    private final ModelProfileRepository models;
    private final ModelSelectionPolicy selectionPolicy;

    /**
     * 创建设置用例服务。
     *
     * @param sessions 会话仓储
     * @param models 模型档案仓储
     * @param selectionPolicy 模型选择策略
     */
    public SessionSettingsApplicationService(SessionRepository sessions,
            ModelProfileRepository models, ModelSelectionPolicy selectionPolicy) {
        this.sessions = sessions;
        this.models = models;
        this.selectionPolicy = selectionPolicy;
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
        ThinkingLevel level = command.thinkingLevel() == null
                ? current.config().thinkingLevel() : command.thinkingLevel();
        selectionPolicy.requireCompatible(profile, false, level);
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

    /**
     * 删除会话及其历史。
     *
     * <p>运行中的会话不允许删除：先停止 run，避免后台线程继续写已删除的会话。
     * entry 与审批记录由数据库外键级联删除。</p>
     *
     * @param sessionId 会话标识
     */
    @Transactional
    public void delete(SessionId sessionId) {
        ChatSession current = require(sessionId);
        Guard.require(current.status() == SessionStatus.IDLE
                        || current.status() == SessionStatus.ARCHIVED,
                DomainErrorCode.INVALID_STATE_TRANSITION, "会话正在运行，请先停止再删除");
        sessions.delete(sessionId);
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
