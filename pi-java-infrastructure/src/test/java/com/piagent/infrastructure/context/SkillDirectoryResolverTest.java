package com.piagent.infrastructure.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Skill 目录解析测试。
 *
 * <p>覆盖用户目录展开、classpath 内置目录解包与失败容错。</p>
 */
class SkillDirectoryResolverTest {

    @TempDir
    Path tempDir;

    private String originalHome;

    @AfterEach
    void restoreHome() {
        if (originalHome != null) {
            System.setProperty("user.home", originalHome);
        }
    }

    @Test
    void expandsHomeDirectory() {
        useHome(tempDir);

        Path expanded = SkillDirectoryResolver.expand("~/skills");

        assertThat(expanded).isEqualTo(tempDir.resolve("skills").toAbsolutePath().normalize());
    }

    @Test
    void materializesClasspathSkillsIntoStableHomeDirectory() throws IOException {
        useHome(tempDir);

        Path root = SkillDirectoryResolver.expand("classpath:bundled-test-skills");

        assertThat(root).isNotNull();
        assertThat(root).isEqualTo(tempDir.resolve(".pi-java/bundled-skills")
                .toAbsolutePath().normalize());
        assertThat(Files.readString(root.resolve("demo-bundled-skill/SKILL.md")))
                .contains("name: demo-bundled-skill")
                .contains("测试用内置技能");
    }

    @Test
    void missingClasspathSkillsAreIgnored() {
        useHome(tempDir);

        assertThat(SkillDirectoryResolver.expand("classpath:no-such-skills")).isNull();
        assertThat(SkillDirectoryResolver.expand("classpath:")).isNull();
    }

    @Test
    void blankConfigurationIsIgnored() {
        assertThat(SkillDirectoryResolver.expand("  ")).isNull();
        assertThat(SkillDirectoryResolver.expand(null)).isNull();
    }

    private void useHome(Path home) {
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
    }
}
