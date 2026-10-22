package com.piagent.application.agent.service;

import com.piagent.domain.context.SkillCatalog;
import com.piagent.domain.context.SkillDescriptor;
import com.piagent.domain.context.SkillPromptFormatter;
import com.piagent.domain.context.port.SkillSource;
import com.piagent.domain.context.port.WorkspacePromptSource;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.tool.model.ToolDescriptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * system prompt 组装器。
 *
 * <p>M4 只注入身份、工作区、工具名与思考级别；M7 扩展为固定顺序的 6 个小节，
 * 详见 {@link PromptSections}。两个外部依赖都是端口：Skill 目录来自文件系统扫描，
 * AGENTS.md 来自工作区根目录，测试时可直接替换。</p>
 *
 * <p>失败容忍：Skill 扫描或 AGENTS.md 读取抛异常时，只跳过对应小节并留痕，
 * 绝不让“读取提示词”这一步阻止 run 启动。</p>
 */
@Component
public class SystemPromptBuilder {

    private static final System.Logger LOG = System.getLogger(SystemPromptBuilder.class.getName());

    private final SkillSource skillSource;
    private final WorkspacePromptSource workspacePromptSource;
    private final SkillPromptFormatter skillFormatter;

    /**
     * 创建组装器。
     *
     * @param skillSource skill 源
     * @param workspacePromptSource 工作区文档源
     * @param skillFormatter skill 目录格式化器
     */
    public SystemPromptBuilder(SkillSource skillSource, WorkspacePromptSource workspacePromptSource,
            SkillPromptFormatter skillFormatter) {
        this.skillSource = skillSource;
        this.workspacePromptSource = workspacePromptSource;
        this.skillFormatter = skillFormatter;
    }

    /**
     * 组装 system prompt。
     *
     * @param session 会话
     * @param tools 本轮可用工具
     * @return 提示词
     */
    public String build(ChatSession session, List<ToolDescriptor> tools) {
        return PromptSections.join(sections(session, tools, null));
    }

    /**
     * 组装带显式技能的 system prompt。
     *
     * <p>{@code /skill:name args} 时使用：把技能正文并入提示词，让模型在本轮拿到完整说明，
     * 而不是再去读一次文件。</p>
     *
     * @param session 会话
     * @param tools 本轮可用工具
     * @param skillName 显式注入的 skill 名称；可为 null
     * @return 提示词
     */
    public String build(ChatSession session, List<ToolDescriptor> tools, String skillName) {
        if (skillName == null || skillName.isBlank()) {
            return build(session, tools);
        }
        Optional<SkillDescriptor> descriptor = skillCatalog().find(skillName);
        if (descriptor.isEmpty()) {
            return build(session, tools);
        }
        return PromptSections.join(sections(session, tools, descriptor.get()));
    }

    /**
     * 当前可用 skill 目录。
     *
     * @return 目录
     */
    public SkillCatalog skillCatalog() {
        try {
            return skillSource.discover();
        } catch (RuntimeException error) {
            LOG.log(System.Logger.Level.WARNING, "扫描 skill 失败，本轮按无 skill 处理", error);
            return SkillCatalog.empty();
        }
    }

    private List<String> sections(ChatSession session, List<ToolDescriptor> tools,
            SkillDescriptor explicit) {
        List<String> sections = new ArrayList<>();
        sections.add(PromptSections.identity());
        sections.add(PromptSections.environment(session.config().cwd().toString(),
                session.config().thinkingLevel().name()));
        sections.add(PromptSections.tools(tools));
        sections.add(PromptSections.skills(skillFormatter.format(skillCatalog())));
        sections.add(PromptSections.agents(agentsDocument(session)));
        if (explicit != null) {
            sections.add(PromptSections.skillBody(explicit.name(), readSkillBody(explicit)));
        }
        return sections;
    }

    private String agentsDocument(ChatSession session) {
        try {
            return workspacePromptSource.readAgentsDocument(session.config().cwd()).orElse("");
        } catch (RuntimeException error) {
            LOG.log(System.Logger.Level.WARNING, "读取 AGENTS.md 失败，跳过该小节", error);
            return "";
        }
    }

    private String readSkillBody(SkillDescriptor skill) {
        try {
            return skillSource.readContent(skill);
        } catch (RuntimeException error) {
            LOG.log(System.Logger.Level.WARNING, "读取 skill 正文失败: " + skill.name(), error);
            return "（技能正文读取失败，请用 read 工具打开 " + skill.path() + "）";
        }
    }
}
