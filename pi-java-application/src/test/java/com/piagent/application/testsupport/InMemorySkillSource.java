package com.piagent.application.testsupport;

import com.piagent.domain.context.SkillCatalog;
import com.piagent.domain.context.SkillDescriptor;
import com.piagent.domain.context.port.SkillSource;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 应用层测试用的内存 Skill 源。
 *
 * <p>默认没有 skill；测试通过 {@link #register(SkillDescriptor, String)} 注入描述与正文，
 * 无需触碰文件系统。</p>
 */
public final class InMemorySkillSource implements SkillSource {

    private final Map<SkillDescriptor, String> entries = new LinkedHashMap<>();

    /**
     * 注册 skill。
     *
     * @param skill 描述
     * @param body 正文
     * @return 自身，便于链式配置
     */
    public InMemorySkillSource register(SkillDescriptor skill, String body) {
        entries.put(skill, body);
        return this;
    }

    @Override
    public SkillCatalog discover() {
        return entries.isEmpty() ? SkillCatalog.empty() : new SkillCatalog(entries.keySet().stream().toList());
    }

    @Override
    public String readContent(SkillDescriptor skill) {
        String body = entries.get(skill);
        if (body == null) {
            throw new IllegalStateException("skill 不存在: " + skill.name());
        }
        return body;
    }

    @Override
    public void refresh() {
        // 内存源没有缓存，无需处理。
    }
}
