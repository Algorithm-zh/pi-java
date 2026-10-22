package com.piagent.domain.context;

import java.util.Objects;

/**
 * 把 Skill 目录格式化为 system prompt 的紧凑列表。
 *
 * <p>只输出 {@code - name: description}，不注入正文；总长度有硬上限，
 * 防止大量第三方 skill 挤占上下文。</p>
 */
public final class SkillPromptFormatter {

    /** system prompt 中 skills 段的最大字符数。 */
    public static final int MAX_CHARS = 4_000;

    private static final String TRUNCATED = "\n…（Skills 目录已截断）";

    /**
     * 格式化目录。
     *
     * @param catalog skill 目录
     * @return 格式化文本；空目录返回空字符串
     */
    public String format(SkillCatalog catalog) {
        Objects.requireNonNull(catalog, "catalog 不能为空");
        if (catalog.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (SkillDescriptor skill : catalog.all()) {
            String line = "- " + skill.name() + ": " + oneLine(skill.description()) + "\n";
            if (text.length() + line.length() > MAX_CHARS) {
                text.append(TRUNCATED);
                break;
            }
            text.append(line);
        }
        return text.toString().stripTrailing();
    }

    private String oneLine(String description) {
        return description.replace('\r', ' ').replace('\n', ' ').trim();
    }
}
