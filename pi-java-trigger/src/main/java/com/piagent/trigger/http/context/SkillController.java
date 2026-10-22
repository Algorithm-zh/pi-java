package com.piagent.trigger.http.context;

import com.piagent.application.context.SkillApplicationService;
import com.piagent.domain.context.SkillDescriptor;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 技能目录控制器。
 *
 * <p>{@code GET /api/v1/skills} 列出目录，{@code ?refresh=true} 强制重新扫描；
 * {@code GET /api/v1/skills/{name}/content} 返回正文，供 UI 预览与 CLI 使用。</p>
 */
@RestController
@RequestMapping("/api/v1/skills")
public class SkillController {

    private final SkillApplicationService skills;

    /**
     * 创建控制器。
     *
     * @param skills 技能用例
     */
    public SkillController(SkillApplicationService skills) {
        this.skills = skills;
    }

    /**
     * 列出技能。
     *
     * @param refresh 是否强制重新扫描
     * @return 技能列表
     */
    @GetMapping
    public List<SkillResponse> list(@RequestParam(name = "refresh", defaultValue = "false")
            boolean refresh) {
        return skills.list(refresh).stream().map(SkillResponse::from).toList();
    }

    /**
     * 查询单个技能。
     *
     * @param name 技能名
     * @return 技能
     */
    @GetMapping("/{name}")
    public SkillResponse find(@PathVariable String name) {
        return skills.find(name)
                .map(SkillResponse::from)
                .orElseThrow(() -> new DomainException(DomainErrorCode.SKILL_NOT_FOUND,
                        "技能不存在: " + name));
    }

    /**
     * 读取技能正文。
     *
     * @param name 技能名
     * @return 正文
     */
    @GetMapping("/{name}/content")
    public SkillContentResponse content(@PathVariable String name) {
        SkillDescriptor skill = skills.find(name)
                .orElseThrow(() -> new DomainException(DomainErrorCode.SKILL_NOT_FOUND,
                        "技能不存在: " + name));
        return new SkillContentResponse(skill.name(), skills.readContent(name));
    }

    /**
     * 技能正文响应。
     *
     * @param name 技能名
     * @param content 正文
     */
    public record SkillContentResponse(String name, String content) {
    }
}
