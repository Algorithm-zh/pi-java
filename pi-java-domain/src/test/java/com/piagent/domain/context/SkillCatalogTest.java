package com.piagent.domain.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Skill 目录与 prompt 格式化测试。
 */
class SkillCatalogTest {

    private final SkillPromptFormatter formatter = new SkillPromptFormatter();

    @Test
    void indexesSkillsAndSortsCatalog() {
        SkillCatalog catalog = new SkillCatalog(List.of(
                skill("zeta", "last"), skill("alpha", "first")));

        assertThat(catalog.size()).isEqualTo(2);
        assertThat(catalog.find("alpha")).isPresent();
        assertThat(catalog.find(" missing ")).isEmpty();
        assertThat(catalog.find(null)).isEmpty();
        assertThat(catalog.all()).extracting(SkillDescriptor::name)
                .containsExactly("alpha", "zeta");
    }

    @Test
    void rejectsDuplicateNames() {
        assertThatThrownBy(() -> new SkillCatalog(List.of(
                skill("alpha", "one"), skill("alpha", "two"))))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.INVALID_ARGUMENT));
    }

    @Test
    void formatsOneLineEntriesAndTruncates() {
        String text = formatter.format(new SkillCatalog(List.of(
                skill("alpha", "line one\nline two"))));

        assertThat(text).isEqualTo("- alpha: line one line two");
        assertThat(formatter.format(SkillCatalog.empty())).isEmpty();
        List<SkillDescriptor> many = java.util.stream.IntStream.range(0, 12)
                .mapToObj(index -> skill("skill-" + index, "x".repeat(500)))
                .toList();
        assertThat(formatter.format(new SkillCatalog(many)))
                .contains("Skills 目录已截断")
                .hasSizeLessThanOrEqualTo(SkillPromptFormatter.MAX_CHARS + 20);
    }

    private SkillDescriptor skill(String name, String description) {
        return new SkillDescriptor(name, description, Path.of("/skills", name, "SKILL.md"),
                "/skills");
    }
}
