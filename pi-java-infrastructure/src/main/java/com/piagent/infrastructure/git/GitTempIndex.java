package com.piagent.infrastructure.git;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;

/**
 * 临时索引文件，避免快照与还原触碰用户真实索引。
 */
final class GitTempIndex implements AutoCloseable {

    private final Path directory;
    private final Path file;

    private GitTempIndex(Path directory) {
        this.directory = directory;
        this.file = directory.resolve("index");
    }

    static GitTempIndex create() {
        try {
            return new GitTempIndex(Files.createTempDirectory("pi-git-index-"));
        } catch (IOException error) {
            throw new UncheckedIOException("创建临时 Git 索引目录失败", error);
        }
    }

    Path file() {
        return file;
    }

    Map<String, String> environment() {
        return Map.of("GIT_INDEX_FILE", file.toAbsolutePath().toString());
    }

    @Override
    public void close() {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Windows 上 git 可能短暂持有句柄；临时目录留给系统清理。
                }
            });
        } catch (IOException ignored) {
            // 同上，清理失败不影响业务结果。
        }
    }
}
