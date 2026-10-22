package com.piagent.application.context;

import com.piagent.domain.context.SkillCatalog;
import com.piagent.domain.context.SkillDescriptor;
import com.piagent.domain.context.port.SkillSource;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 技能查询用例。
 *
 * <p>对外提供三件事：目录（只含名称与描述）、单个技能正文、强制刷新缓存。
 * 正文读取失败时抛领域异常而不是返回空串，避免调用方把“读失败”误当成“空技能”。</p>
 */
@Service
public class SkillApplicationService {

    private final SkillSource skillSource;

    /**
     * 创建用例服务。
     *
     * @param skillSource 技能源
     */
    public SkillApplicationService(SkillSource skillSource) {
        this.skillSource = skillSource;
    }

    /**
     * 列出技能。
     *
     * @param refresh 是否在读取前强制重新扫描
     * @return 按名称排序的不可变列表
     */
    public List<SkillDescriptor> list(boolean refresh) {
        if (refresh) {
            skillSource.refresh();
        }
        return skillSource.discover().all();
    }

    /**
     * 查询单条技能。
     *
     * @param name 技能名
     * @return 技能；不存在时为空
     */
    public Optional<SkillDescriptor> find(String name) {
        return skillSource.discover().find(name);
    }

    /**
     * 读取技能正文。
     *
     * @param name 技能名
     * @return 去掉 frontmatter 的正文
     * @throws DomainException 技能不存在或读取失败时
     */
    public String readContent(String name) {
        SkillCatalog catalog = skillSource.discover();
        SkillDescriptor skill = catalog.find(name)
                .orElseThrow(() -> new DomainException(DomainErrorCode.SKILL_NOT_FOUND,
                        "技能不存在: " + name));
        try {
            return skillSource.readContent(skill);
        } catch (RuntimeException error) {
            throw new DomainException(DomainErrorCode.INVALID_STATE_TRANSITION,
                    "技能正文读取失败: " + name, error);
        }
    }
}
