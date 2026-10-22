package com.piagent.application.agent.service;

import com.piagent.domain.context.SkillCatalog;
import com.piagent.domain.context.SkillDescriptor;
import java.util.Objects;
import java.util.Optional;

/**
 * 把用户输入中的 {@code /skill:name args} 展开为可发送、可落库的文本。
 *
 * <p>为什么单独成类：技能指令属于“输入语法”，与 Agent Loop 的状态机无关；
 * 它需要在开始 run 之前完成（消息要按展开后的内容落库），并同时告知调用方
 * “本轮显式注入了哪个技能”，以便 system prompt 一并带上正文。</p>
 *
 * <p>失败回退：技能不存在、正文读取失败时原样返回输入并标记未命中，
 * 让模型看到用户原文并自行回应，而不是让整次对话失败。</p>
 */
final class SkillInvocationResolver {

    private static final System.Logger LOG =
            System.getLogger(SkillInvocationResolver.class.getName());

    private final TurnPipeline pipeline;

    /**
     * 创建解析器。
     *
     * @param pipeline turn 流水线（提供技能目录与正文读取）
     */
    SkillInvocationResolver(TurnPipeline pipeline) {
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline 不能为空");
    }

    /**
     * 解析结果。
     *
     * @param text 实际发送与落库的文本
     * @param skill 命中的技能；未命中为 null
     */
    record Resolved(String text, SkillDescriptor skill) {
    }

    /**
     * 解析输入。
     *
     * @param raw 原始输入
     * @return 解析结果
     */
    Resolved resolve(String raw) {
        SkillCatalog catalog = pipeline.skillCatalog();
        Optional<SkillCommand> command = SkillCommand.parse(raw, catalog);
        if (command.isEmpty()) {
            return new Resolved(raw, null);
        }
        Optional<SkillDescriptor> skill = catalog.find(command.get().skillName());
        if (skill.isEmpty()) {
            return new Resolved(raw, null);
        }
        try {
            String body = pipeline.readSkillBody(skill.get());
            return new Resolved(SkillCommand.render(skill.get().name(), command.get().args(), body),
                    skill.get());
        } catch (RuntimeException error) {
            LOG.log(System.Logger.Level.WARNING, "读取 skill 正文失败: " + command.get().skillName(),
                    error);
            return new Resolved(raw, null);
        }
    }
}
