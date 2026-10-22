package com.piagent.domain.context;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 不可变 Skill 目录。
 *
 * <p>按 name 建立索引；重名视为配置错误。顺序保留扫描顺序，格式化时再按名称
 * 排序，保证 system prompt 稳定可测试。</p>
 */
public final class SkillCatalog {

    private final Map<String, SkillDescriptor> byName;

    /**
     * 创建目录。
     *
     * @param skills skill 列表
     */
    public SkillCatalog(List<SkillDescriptor> skills) {
        Objects.requireNonNull(skills, "skills 不能为空");
        Map<String, SkillDescriptor> index = new LinkedHashMap<>();
        for (SkillDescriptor skill : skills) {
            Objects.requireNonNull(skill, "skill 不能为空");
            Guard.require(index.putIfAbsent(skill.name(), skill) == null,
                    DomainErrorCode.INVALID_ARGUMENT, "skill 重名: " + skill.name());
        }
        this.byName = Map.copyOf(index);
    }

    /**
     * 空目录。
     *
     * @return 空目录
     */
    public static SkillCatalog empty() {
        return new SkillCatalog(List.of());
    }

    /**
     * 按名称查询。
     *
     * @param name skill 名称
     * @return 描述；不存在时为空
     */
    public Optional<SkillDescriptor> find(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(byName.get(name.trim()));
    }

    /**
     * 所有 skill，按名称排序。
     *
     * @return 不可变列表
     */
    public List<SkillDescriptor> all() {
        return byName.values().stream()
                .sorted((left, right) -> left.name().compareTo(right.name()))
                .toList();
    }

    /**
     * 是否为空。
     *
     * @return true 表示没有 skill
     */
    public boolean isEmpty() {
        return byName.isEmpty();
    }

    /**
     * skill 数量。
     *
     * @return 数量
     */
    public int size() {
        return byName.size();
    }
}
