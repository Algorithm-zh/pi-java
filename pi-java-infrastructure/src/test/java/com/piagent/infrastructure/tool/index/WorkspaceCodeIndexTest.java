package com.piagent.infrastructure.tool.index;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceCodeIndexTest {

    @TempDir
    Path root;

    private final WorkspaceCodeIndex index = new WorkspaceCodeIndex();

    @Test
    void returnsOverviewWithoutQuery() throws IOException {
        write("README.md", "# Demo\n\nproject docs\n");
        write("pom.xml", "<project/>\n");
        write("src/main/java/com/demo/App.java", "public class App {\n}\n");
        write("src/main/java/com/demo/TokenService.java",
                "public class TokenService {\n    String token;\n}\n");
        write("src/main/java/com/demo/TokenStore.java", "public class TokenStore {\n}\n");
        write("docs/guide.md", "# Guide\n");
        Files.write(root.resolve("blob.bin"), new byte[] {'h', 0, 'i'});

        RepoMap map = index.search(root, "", false, "", 20);

        assertThat(map.text()).contains("仓库地图", "已索引 6 个文本文件", "跳过二进制 1 个",
                "语言分布", "- src/（3 个文件）", "- docs/（1 个文件）",
                "根目录文件：README.md、pom.xml", "关键文件", "README.md（说明与约定）",
                "pom.xml（构建与依赖）", "提示：");
        assertThat(map.matchedFiles()).isEqualTo(6);
        assertThat(map.indexedFiles()).isEqualTo(6);
        assertThat(map.truncated()).isFalse();
    }

    @Test
    void ranksFilesByQuery() throws IOException {
        write("src/main/java/com/demo/TokenService.java",
                "public class TokenService {\n    String token;\n}\n");
        write("docs/theme.md", "# Theme\n\ncolors and spacing\n");

        RepoMap map = index.search(root, "", false, "token", 20);

        assertThat(map.text()).startsWith("查询「token」：命中 1 个文件")
                .contains("1. src/main/java/com/demo/TokenService.java")
                .contains("public class TokenService");
        assertThat(map.matchedFiles()).isEqualTo(1);
    }

    @Test
    void limitsResultsToDirectoryScope() throws IOException {
        write("auth/TokenStore.java", "class TokenStore {\n    String token;\n}\n");
        write("ui/Theme.java", "class Theme {\n    String token;\n}\n");

        RepoMap map = index.search(root, "auth", false, "token", 20);

        assertThat(map.text()).contains("范围：auth/", "auth/TokenStore.java")
                .doesNotContain("ui/Theme.java");
        assertThat(map.matchedFiles()).isEqualTo(1);
    }

    @Test
    void limitsScopeToSingleFile() throws IOException {
        write("auth/TokenStore.java", "class TokenStore {\n    String token;\n}\n");
        write("ui/Theme.java", "class Theme {\n    String token;\n}\n");

        RepoMap map = index.search(root, "ui/Theme.java", true, "token", 20);

        assertThat(map.text()).contains("范围：ui/Theme.java", "ui/Theme.java")
                .doesNotContain("auth/TokenStore.java");
    }

    @Test
    void reportsNoMatches() throws IOException {
        write("notes.txt", "alpha\n");

        RepoMap map = index.search(root, "", false, "zzzz", 20);

        assertThat(map.text()).contains("命中 0 个文件", "没有找到相关文件");
        assertThat(map.matchedFiles()).isZero();
    }

    @Test
    void limitsShownFiles() throws IOException {
        write("a.txt", "shared term\n");
        write("b.txt", "shared term\n");
        write("c.txt", "shared term\n");

        RepoMap map = index.search(root, "", false, "shared", 2);

        assertThat(map.text()).contains("命中 3 个文件，显示前 2 个");
        assertThat(map.matchedFiles()).isEqualTo(3);
    }

    @Test
    void rebuildsIndexAfterFileChanges() throws IOException {
        Path file = write("notes.txt", "alpha\n");

        assertThat(index.search(root, "", false, "alpha", 20).matchedFiles()).isEqualTo(1);
        assertThat(index.search(root, "", false, "beta", 20).matchedFiles()).isZero();

        Files.writeString(file, "beta content\n");

        assertThat(index.search(root, "", false, "beta", 20).matchedFiles()).isEqualTo(1);
        assertThat(index.search(root, "", false, "alpha", 20).matchedFiles()).isZero();
    }

    @Test
    void skipsHiddenAndBuildDirectories() throws IOException {
        write("src/App.java", "class App {\n}\n");
        write(".hidden/Secret.java", "class Secret {\n}\n");
        write("target/Generated.java", "class Generated {\n}\n");

        RepoMap map = index.search(root, "", false, "", 20);

        assertThat(map.text()).contains("src/").doesNotContain(".hidden", "target/");
        assertThat(map.indexedFiles()).isEqualTo(1);
    }

    private Path write(String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content);
    }
}
