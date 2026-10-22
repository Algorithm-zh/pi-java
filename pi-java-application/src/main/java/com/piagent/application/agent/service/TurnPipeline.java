package com.piagent.application.agent.service;

import com.piagent.domain.agent.RunId;
import com.piagent.domain.context.SkillCatalog;
import com.piagent.domain.context.SkillDescriptor;
import com.piagent.domain.context.port.SkillSource;
import com.piagent.domain.model.ModelProfile;
import com.piagent.domain.model.llm.LlmRequest;
import com.piagent.domain.model.llm.LlmTool;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.ProjectedContext;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.port.SessionEntryStore;
import com.piagent.domain.session.service.SessionContextProjector;
import com.piagent.domain.tool.port.ToolRegistry;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Component;

/**
 * 单个 turn 的装配流水线。
 *
 * <p>把“读日志 → 投影上下文 → 组装请求 → 流式调用 → 执行工具”串起来，
 * 但不做状态推进与终止判定；这些由 {@link AgentLoopService} 负责。流水线本身
 * 无状态，可在多个 run 之间复用。</p>
 *
 * <p>M7 起支持可选技能：{@code /skill:name args} 时，技能正文会并入 system prompt，
 * 同时作为本轮用户消息的补充说明传给模型（见 {@link SkillCommand}）。</p>
 */
@Component
public class TurnPipeline {

    private final SessionEntryStore entryStore;
    private final SessionContextProjector projector;
    private final SystemPromptBuilder promptBuilder;
    private final TurnExecutor turnExecutor;
    private final ToolCallCoordinator toolCoordinator;
    private final SkillSource skillSource;

    /**
     * 创建流水线。
     *
     * @param entryStore entry 存储
     * @param projector 上下文投影器
     * @param promptBuilder 提示词组装器
     * @param turnExecutor 流式执行器
     * @param toolCoordinator 工具协调器
     * @param skillSource skill 源
     */
    public TurnPipeline(SessionEntryStore entryStore, SessionContextProjector projector,
            SystemPromptBuilder promptBuilder, TurnExecutor turnExecutor,
            ToolCallCoordinator toolCoordinator, SkillSource skillSource) {
        this.entryStore = entryStore;
        this.projector = projector;
        this.promptBuilder = promptBuilder;
        this.turnExecutor = turnExecutor;
        this.toolCoordinator = toolCoordinator;
        this.skillSource = skillSource;
    }

    /**
     * 从会话当前分支投影上下文。
     *
     * @param session 会话快照
     * @return 投影结果；空会话返回空上下文
     */
    public ProjectedContext project(ChatSession session) {
        if (session.leafEntryId() == null) {
            return ProjectedContext.empty();
        }
        List<SessionEntry> branch = entryStore.loadBranch(
                session.identity().id(), session.leafEntryId());
        return projector.project(branch, session.leafEntryId());
    }

    /**
     * 组装模型请求。
     *
     * @param session 会话快照
     * @param profile 模型档案
     * @param context 投影上下文
     * @param tools 本次 run 的工具快照
     * @param explicitSkill 本轮显式注入的技能；可为 null
     * @return 请求
     */
    public LlmRequest request(ChatSession session, ModelProfile profile, ProjectedContext context,
            ToolRegistry tools, SkillDescriptor explicitSkill) {
        String prompt = explicitSkill == null
                ? promptBuilder.build(session, tools.descriptors())
                : promptBuilder.build(session, tools.descriptors(), explicitSkill.name());
        return new LlmRequest(profile, prompt, context.messages(), llmTools(tools),
                session.config().thinkingLevel());
    }

    /**
     * 组装模型请求（无显式技能）。
     *
     * @param session 会话快照
     * @param profile 模型档案
     * @param context 投影上下文
     * @param tools 本次 run 的工具快照
     * @return 请求
     */
    public LlmRequest request(ChatSession session, ModelProfile profile, ProjectedContext context,
            ToolRegistry tools) {
        return request(session, profile, context, tools, null);
    }

    /**
     * 按名称解析技能。
     *
     * @param name 技能名
     * @return 描述；不存在时为空
     */
    public Optional<SkillDescriptor> findSkill(String name) {
        return promptBuilder.skillCatalog().find(name);
    }

    /**
     * 当前技能目录。
     *
     * @return 目录
     */
    public SkillCatalog skillCatalog() {
        return promptBuilder.skillCatalog();
    }

    /**
     * 读取技能正文。
     *
     * @param skill 技能描述
     * @return 正文
     */
    public String readSkillBody(SkillDescriptor skill) {
        return skillSource.readContent(skill);
    }

    /**
     * 流式执行模型请求。
     *
     * @param request 请求
     * @param runId 运行标识
     * @param events 事件发布器
     * @param cancelled 取消查询
     * @return 流式结果
     */
    public TurnResult stream(LlmRequest request, RunId runId, RunEventPublisher events,
            BooleanSupplier cancelled) {
        events.messageStarted();
        return turnExecutor.execute(request, runId, events, cancelled);
    }

    /**
     * 执行一批工具调用。
     *
     * @param context 批次上下文
     * @return 批次摘要
     */
    public ToolBatchResult executeTools(ToolBatchContext context) {
        return toolCoordinator.execute(context);
    }

    private List<LlmTool> llmTools(ToolRegistry tools) {
        return tools.descriptors().stream()
                .map(tool -> new LlmTool(tool.name().toString(), tool.description(),
                        tool.inputSchemaJson()))
                .toList();
    }
}
