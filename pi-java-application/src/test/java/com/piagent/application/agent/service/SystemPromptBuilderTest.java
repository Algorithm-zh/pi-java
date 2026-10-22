package com.piagent.application.agent.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.piagent.application.testsupport.InMemorySkillSource;
import com.piagent.application.testsupport.SessionEntryFactory;
import com.piagent.application.testsupport.StubWorkspacePromptSource;
import com.piagent.domain.context.SkillDescriptor;
import com.piagent.domain.context.SkillPromptFormatter;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.SandboxSnapshot;
import com.piagent.domain.session.model.SessionConfig;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.ThinkingLevel;
import com.piagent.domain.tool.model.ExecutionMode;
import com.piagent.domain.tool.model.ToolDescriptor;
import com.piagent.domain.tool.model.ToolName;
import com.piagent.domain.tool.model.ToolRisk;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * system prompt 组装测试。
 *
 * <p>验证小节顺序、工具风险展示、Skills 目录只含名称与描述、AGENTS.md 注入、
 * 显式 {@code /skill:} 正文注入，以及外部读取失败时的容错。</p>
 */
class SystemPromptBuilderTest {

    private static final Path WORKSPACE = Path.of("target/prompt-test").toAbsolutePath();

    @Test
    void includesIdentityEnvironmentAndTools() {
        String prompt = builder(new InMemorySkillSource(), new StubWorkspacePromptSource())
                .build(session(), List.of(tool("read", ToolRisk.LOW)));

        assertThat(prompt).contains("你是 pi-java")
                .contains("工作目录：" + WORKSPACE)
                .contains("当前思考级别：OFF")
                .contains("- read（low）: 读取文件");
    }

    @Test
    void rendersEmptyToolList() {
        String prompt = builder(new InMemorySkillSource(), new StubWorkspacePromptSource())
                .build(session(), List.of());

        assertThat(prompt).contains("可用工具：（无）");
    }

    @Test
    void listsSkillsWithoutBodies() {
        InMemorySkillSource skills = new InMemorySkillSource().register(
                new SkillDescriptor("code-review", "按规范审查", WORKSPACE.resolve("SKILL.md"),
                        "test"), "很长的正文");

        String prompt = builder(skills, new StubWorkspacePromptSource())
                .build(session(), List.of());

        assertThat(prompt).contains("- code-review: 按规范审查");
        assertThat(prompt).doesNotContain("很长的正文");
    }

    @Test
    void injectsAgentsDocument() {
        String prompt = builder(new InMemorySkillSource(),
                new StubWorkspacePromptSource().withContent("先写测试再写实现"))
                .build(session(), List.of());

        assertThat(prompt).contains("工作区约定（AGENTS.md）：").contains("先写测试再写实现");
    }

    @Test
    void injectsExplicitSkillBody() {
        InMemorySkillSource skills = new InMemorySkillSource().register(
                new SkillDescriptor("deploy", "发布流程", WORKSPACE.resolve("SKILL.md"), "test"),
                "第一步：打包");

        String prompt = builder(skills, new StubWorkspacePromptSource())
                .build(session(), List.of(), "deploy");

        assertThat(prompt).contains("技能 deploy 的完整说明：").contains("第一步：打包");
    }

    @Test
    void ignoresUnknownExplicitSkill() {
        String prompt = builder(new InMemorySkillSource(), new StubWorkspacePromptSource())
                .build(session(), List.of(), "missing");

        assertThat(prompt).doesNotContain("技能 missing");
    }

    @Test
    void toleratesFailingWorkspaceSource() {
        String prompt = builder(new InMemorySkillSource(),
                new StubWorkspacePromptSource().failing(new java.io.UncheckedIOException(
                        new java.io.IOException("读不了"))))
                .build(session(), List.of());

        assertThat(prompt).contains("你是 pi-java");
        assertThat(prompt).doesNotContain("AGENTS.md");
    }

    private SystemPromptBuilder builder(InMemorySkillSource skills,
            StubWorkspacePromptSource workspace) {
        return new SystemPromptBuilder(skills, workspace, new SkillPromptFormatter());
    }

    private ChatSession session() {
        return ChatSession.createRoot(SessionEntryFactory.sessionId(1), "prompt 测试",
                new SessionConfig(WORKSPACE, "fake", ThinkingLevel.OFF,
                        SandboxSnapshot.forWorkspace(WORKSPACE)));
    }

    private ToolDescriptor tool(String name, ToolRisk risk) {
        return new ToolDescriptor(new ToolName(name), "读取文件\n多行描述",
                "{\"type\":\"object\"}", risk, ExecutionMode.PARALLEL, Duration.ofSeconds(5));
    }
}
