package com.piagent.domain.context.port;

import com.piagent.domain.context.SkillCatalog;
import com.piagent.domain.context.SkillDescriptor;

/**
 * Skill 文件系统端口。
 *
 * <p>扫描、frontmatter 解析与缓存都在 infrastructure；领域层只看到不可变目录
 * 与按需读取的正文。</p>
 */
public interface SkillSource {

    /**
     * 发现所有 skill。
     *
     * @return 不可变目录
     */
    SkillCatalog discover();

    /**
     * 读取 skill 正文，去掉 frontmatter。
     *
     * @param skill skill 描述
     * @return 正文
     */
    String readContent(SkillDescriptor skill);

    /**
     * 清空缓存，强制下次重新扫描。
     */
    void refresh();
}
