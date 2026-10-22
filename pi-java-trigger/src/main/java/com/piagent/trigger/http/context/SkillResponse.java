package com.piagent.trigger.http.context;

import com.piagent.domain.context.SkillDescriptor;

/**
 * 技能的 HTTP 表示。
 *
 * <p>只暴露名称、描述、路径与来源；正文需要单独请求 {@code /skills/{name}/content}，
 * 避免列表接口把大量 Markdown 一次推给前端。</p>
 *
 * @param name 名称
 * @param description 一行描述
 * @param path SKILL.md 绝对路径
 * @param source 来源目录
 */
public record SkillResponse(String name, String description, String path, String source) {

    /**
     * 转换技能描述。
     *
     * @param skill 技能
     * @return 响应
     */
    public static SkillResponse from(SkillDescriptor skill) {
        return new SkillResponse(skill.name(), skill.description(), skill.path().toString(),
                skill.source());
    }
}
