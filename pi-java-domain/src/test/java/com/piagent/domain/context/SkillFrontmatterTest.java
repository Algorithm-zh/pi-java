package com.piagent.domain.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import org.junit.jupiter.api.Test;

/**
 * SKILL.md frontmatter 解析测试。
 */
class SkillFrontmatterTest {

    @Test
    void parsesFrontmatterAndBody() {
        SkillFrontmatter.Parsed parsed = SkillFrontmatter.parse("""
                ---
                name: Code-Review
                description: "按团队规范审查改动"
                ---
                # 正文标题
                这里是正文。
                """, "review");

        assertThat(parsed.name()).isEqualTo("code-review");
        assertThat(parsed.description()).isEqualTo("按团队规范审查改动");
        assertThat(parsed.body()).isEqualTo("# 正文标题\n这里是正文。");
    }

    @Test
    void fallsBackToDirectoryNameWhenNameMissing() {
        SkillFrontmatter.Parsed parsed = SkillFrontmatter.parse("""
                ---
                description: 没有 name
                ---
                正文
                """, "Deploy_Robot");

        assertThat(parsed.name()).isEqualTo("deploy-robot");
    }

    @Test
    void derivesDescriptionFromBodyWhenMissing() {
        SkillFrontmatter.Parsed parsed = SkillFrontmatter.parse("""
                # 标题
                第一段说明文字。

                第二段
                """, "plain");

        assertThat(parsed.description()).isEqualTo("第一段说明文字。");
    }

    @Test
    void usesFallbackDescriptionWhenNothingUsable() {
        SkillFrontmatter.Parsed parsed = SkillFrontmatter.parse("\n\n", "empty");

        assertThat(parsed.description()).isEqualTo("（未提供 description）");
        assertThat(parsed.body()).isEmpty();
    }

    @Test
    void handlesDocumentWithoutFrontmatter() {
        SkillFrontmatter.Parsed parsed = SkillFrontmatter.parse("直接是正文", "direct");

        assertThat(parsed.name()).isEqualTo("direct");
        assertThat(parsed.body()).isEqualTo("直接是正文");
    }

    @Test
    void ignoresNestedAndCommentLines() {
        SkillFrontmatter.Parsed parsed = SkillFrontmatter.parse("""
                ---
                # 注释
                name: nested
                metadata:
                  - item
                ~invalid line
                ---
                body
                """, "fallback");

        assertThat(parsed.name()).isEqualTo("nested");
    }

    @Test
    void truncatesLongDescription() {
        String longDescription = "描述".repeat(400);

        SkillFrontmatter.Parsed parsed = SkillFrontmatter.parse(
                "---\ndescription: " + longDescription + "\n---\nbody", "long");

        assertThat(parsed.description()).endsWith("…");
        assertThat(parsed.description().length())
                .isLessThanOrEqualTo(SkillFrontmatter.MAX_DESCRIPTION_LENGTH + 1);
    }

    @Test
    void rejectsUnusableName() {
        assertThatThrownBy(() -> SkillFrontmatter.normalizeName("!!! ###"))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.INVALID_ARGUMENT));
        assertThatThrownBy(() -> SkillFrontmatter.parse("正文", "!!!"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsOverlongName() {
        assertThatThrownBy(() -> SkillFrontmatter.normalizeName("a".repeat(65)))
                .isInstanceOf(DomainException.class);
    }
}
