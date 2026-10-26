package com.piagent.application.agent.service;

import com.piagent.application.git.RunGitTracker;
import com.piagent.domain.context.ContextBudget;
import com.piagent.domain.context.SkillDescriptor;
import com.piagent.domain.model.ModelProfile;
import com.piagent.domain.session.model.SessionId;

/**
 * 一次 run 的稳定上下文。
 *
 * <p>run 开始后，模型档案、工具快照、会话标识、上下文预算、显式技能、Git 跟踪器
 * 与自动验证状态都不再变化；把它们打包成一个对象，让循环里的每个步骤只关心“会变的东西”
 * （轮数、会话快照、当前请求）。这也是控制单文件规模的关键：不打包就会出现
 * 6 个以上参数的方法。</p>
 *
 * @param sessionId 会话标识
 * @param profile 模型档案
 * @param reserved 运行句柄（含工具快照与事件发布器）
 * @param budget 上下文预算
 * @param explicitSkill 本轮显式注入的技能；可为 null
 * @param git 本次 run 的工作区跟踪器
 * @param verification 本次 run 的自动验证步骤
 */
record RunContext(
        SessionId sessionId,
        ModelProfile profile,
        ReservedRun reserved,
        ContextBudget budget,
        SkillDescriptor explicitSkill,
        RunGitTracker git,
        RunVerificationStep verification) {

    /**
     * 事件发布器。
     *
     * @return 发布器
     */
    RunEventPublisher events() {
        return reserved.events();
    }
}
