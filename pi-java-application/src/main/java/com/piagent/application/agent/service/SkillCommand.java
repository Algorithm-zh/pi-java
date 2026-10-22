package com.piagent.application.agent.service;

import com.piagent.domain.context.SkillCatalog;
import com.piagent.domain.context.SkillDescriptor;
import java.util.Optional;

/**
 * 用户输入中的斜杠指令。
 *
 * <p>解析规则刻意保持简单：只有以 {@code /} 开头、且第一段（到空白为止）精确匹配
 * 某个 {@link SkillDescriptor#name()} 时才视为技能指令，避免把用户随手输入的
 * {@code /api/v1/users}、{@code /read README.md} 之类误判。文件路径仍按普通消息
 * 交给模型与工具处理。</p>
 *
 * @param raw 原始输入
 * @param skillName 命中的 skill 名称
 * @param args 指令后的剩余参数
 */
record SkillCommand(String raw, String skillName, String args) {

    private static final String PREFIX = "/skill:";

    /**
     * 尝试解析技能指令。
     *
     * @param raw 用户输入
     * @param catalog 当前 skill 目录
     * @return 命中的指令；不是技能指令时为空
     */
    static Optional<SkillCommand> parse(String raw, SkillCatalog catalog) {
        if (raw == null || catalog == null || catalog.isEmpty()) {
            return Optional.empty();
        }
        String text = raw.strip();
        if (!text.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            return Optional.empty();
        }
        String rest = text.substring(PREFIX.length()).strip();
        if (rest.isEmpty()) {
            return Optional.empty();
        }
        String name = firstToken(rest);
        String args = rest.substring(name.length()).strip();
        Optional<SkillDescriptor> skill = catalog.find(name);
        return skill.map(descriptor -> new SkillCommand(text, descriptor.name(), args));
    }

    /**
     * 装配注入技能后真正发给模型的消息。
     *
     * @param skillName 技能名
     * @param args 附加参数
     * @param body 技能正文
     * @return 用户消息文本
     */
    static String render(String skillName, String args, String body) {
        StringBuilder text = new StringBuilder();
        text.append("请按照技能「").append(skillName).append("」执行。\n\n")
                .append(body.strip());
        if (args != null && !args.isBlank()) {
            text.append("\n\n本次任务：").append(args.strip());
        }
        return text.toString();
    }

    private static String firstToken(String value) {
        int end = 0;
        while (end < value.length() && !Character.isWhitespace(value.charAt(end))) {
            end++;
        }
        return value.substring(0, end);
    }
}
