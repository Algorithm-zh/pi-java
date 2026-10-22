package com.piagent.domain.context;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 结构化压缩摘要正文。
 *
 * <p>为什么不用自由文本：自由文本摘要无法稳定断言“是否漏掉了下一步”，也无法在
 * 二次压缩时只搬运关键小节。固定小节 + 文件清单让摘要既可读、可测，也便于收敛
 * 判断（裁剪时优先丢弃“进展”，永不丢弃“目标”与“下一步”）。</p>
 *
 * @param sections 各小节内容，缺失的小节视为空
 * @param readFiles 本段历史中读取过的文件，按出现顺序去重
 * @param modifiedFiles 本段历史中修改过的文件，按出现顺序去重
 */
public record SummaryDraft(Map<SummarySection, String> sections,
        List<String> readFiles, List<String> modifiedFiles) {

    /**
     * 校验摘要草稿。
     *
     * @param sections 小节内容
     * @param readFiles 读过的文件
     * @param modifiedFiles 改过的文件
     */
    public SummaryDraft {
        Objects.requireNonNull(sections, "sections 不能为空");
        sections = Map.copyOf(sections);
        readFiles = readFiles == null ? List.of() : List.copyOf(readFiles);
        modifiedFiles = modifiedFiles == null ? List.of() : List.copyOf(modifiedFiles);
    }

    /**
     * 空草稿：模型没有产出任何小节时使用。
     *
     * @return 空草稿
     */
    public static SummaryDraft empty() {
        return new SummaryDraft(Map.of(), List.of(), List.of());
    }

    /**
     * 取小节内容。
     *
     * @param section 小节
     * @return 内容；不存在时为空字符串
     */
    public String section(SummarySection section) {
        Objects.requireNonNull(section, "section 不能为空");
        String value = sections.get(section);
        return value == null ? "" : value.strip();
    }

    /**
     * 是否所有小节都为空。
     *
     * @return true 表示没有有效内容
     */
    public boolean isEmpty() {
        return sections.values().stream().allMatch(value -> value == null || value.isBlank());
    }

    /**
     * 替换某个小节。
     *
     * @param section 小节
     * @param content 新内容
     * @return 新草稿
     */
    public SummaryDraft withSection(SummarySection section, String content) {
        Objects.requireNonNull(section, "section 不能为空");
        Guard.notBlank(content, DomainErrorCode.INVALID_ARGUMENT, "摘要小节内容不能为空");
        Map<SummarySection, String> updated = new EnumMap<>(SummarySection.class);
        updated.putAll(sections);
        updated.put(section, content);
        return new SummaryDraft(updated, readFiles, modifiedFiles);
    }
}
