package com.piagent.application.agent.service;

import com.piagent.application.verify.VerificationService;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.payload.VerificationPayload;

/**
 * run 结束前的自动验证。
 *
 * <p>只在本次 run 真的改过文件、模型准备收尾时执行一次；失败时把输出回写成
 * 一条用户消息，让循环继续一轮。每个 run 最多回写一次，避免验证失败时无限重试。</p>
 */
final class RunVerificationStep {

    private static final System.Logger LOG =
            System.getLogger(RunVerificationStep.class.getName());

    private final VerificationService verification;
    private int attempts;

    /**
     * 创建步骤。
     *
     * @param verification 验证服务
     */
    RunVerificationStep(VerificationService verification) {
        this.verification = verification;
    }

    /**
     * 在模型准备结束 run 时尝试验证。
     *
     * @param session 会话快照
     * @param mutated 本次 run 是否执行过写入类工具
     * @return true 表示已回写失败输出，应继续下一轮
     */
    boolean beforeFinish(ChatSession session, boolean mutated) {
        if (!mutated || attempts > 0 || !verification.enabled()) {
            return false;
        }
        attempts++;
        SessionId sessionId = session.identity().id();
        VerificationPayload result;
        try {
            result = verification.verify(sessionId, session.config().cwd());
        } catch (RuntimeException error) {
            LOG.log(System.Logger.Level.WARNING, "自动验证执行失败: " + sessionId, error);
            return false;
        }
        if (result.passed() || !verification.retryOnFailure()) {
            return false;
        }
        try {
            verification.appendFollowUp(sessionId, result);
            return true;
        } catch (RuntimeException error) {
            LOG.log(System.Logger.Level.WARNING, "回写验证失败结果失败: " + sessionId, error);
            return false;
        }
    }
}
