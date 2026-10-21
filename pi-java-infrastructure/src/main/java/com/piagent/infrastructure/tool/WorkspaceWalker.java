package com.piagent.infrastructure.tool;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 有界工作区遍历器。
 *
 * <p>ls/grep/glob 都需要遍历，但绝不能把 node_modules 或 .git 整个扫一遍。
 * 统一规则：跳过隐藏目录与常见产物目录；不跟随符号链接；同时限制目录数与
 * 结果数，达到上限时返回 {@code truncated=true}，让调用方明确告知模型结果不完整。</p>
 */
public final class WorkspaceWalker {

    /** 常见无需遍历的目录（隐藏目录统一跳过）。 */
    private static final Set<String> SKIPPED_DIRECTORIES = Set.of(
            "target", "build", "dist", "out", "node_modules", "vendor",
            "coverage", "__pycache__", ".venv", "venv");

    private static final int MAX_VISITED_DIRECTORIES = 20_000;

    private WorkspaceWalker() {
    }

    /**
     * 遍历结果。
     *
     * @param paths 命中的路径（未排序）
     * @param truncated 是否因上限截断
     */
    public record WalkResult(List<Path> paths, boolean truncated) {

        /**
         * 冻结结果。
         *
         * @param paths 路径
         * @param truncated 是否截断
         */
        public WalkResult {
            paths = List.copyOf(paths);
        }
    }

    /**
     * 遍历工作区。
     *
     * @param root 起点（必须是目录）
     * @param maxDepth 最大深度，1 表示只看直接子项
     * @param maxEntries 最多返回的路径数
     * @param includeDirectories 是否把目录也放入结果
     * @return 遍历结果
     * @throws IOException 读取目录失败
     */
    public static WalkResult walk(Path root, int maxDepth, int maxEntries,
            boolean includeDirectories) throws IOException {
        Objects.requireNonNull(root, "root 不能为空");
        List<Path> collected = new ArrayList<>();
        boolean[] truncated = {false};
        int[] visitedDirectories = {0};
        Files.walkFileTree(root, Set.of(), maxDepth, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(root) && isSkippedDirectory(dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (++visitedDirectories[0] > MAX_VISITED_DIRECTORIES) {
                    truncated[0] = true;
                    return FileVisitResult.TERMINATE;
                }
                if (includeDirectories && !dir.equals(root)) {
                    collected.add(dir);
                    if (collected.size() >= maxEntries) {
                        truncated[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                boolean directory = attrs.isDirectory();
                if (directory && (!includeDirectories || isSkippedDirectory(file))) {
                    return FileVisitResult.CONTINUE;
                }
                if (directory || attrs.isRegularFile()) {
                    collected.add(file);
                    if (collected.size() >= maxEntries) {
                        truncated[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return new WalkResult(collected, truncated[0]);
    }

    /**
     * 判断目录是否应跳过。
     *
     * @param directory 目录
     * @return true 表示跳过
     */
    public static boolean isSkippedDirectory(Path directory) {
        Path name = directory.getFileName();
        if (name == null) {
            return false;
        }
        String text = name.toString();
        return text.startsWith(".") || SKIPPED_DIRECTORIES.contains(text);
    }
}
