package com.piagent.infrastructure.tool.index;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 仓库概览格式化：语言分布、一层目录结构与关键文件。
 *
 * <p>概览回答“这个仓库有什么”，只保留能在上下文里读完的信息：目录只列一层、
 * 关键文件最多十个，并提示改用带 query 的调用继续定位。</p>
 */
final class RepoMapOverview {

    private static final int MAX_LANGUAGES = 8;
    private static final int MAX_DIRECTORIES = 20;
    private static final int MAX_ROOT_FILES = 10;
    private static final int MAX_KEY_FILES = 10;

    private static final Set<String> DOC_FILES = Set.of(
            "readme.md", "readme", "agents.md", "contributing.md");
    private static final Set<String> BUILD_FILES = Set.of(
            "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle",
            "package.json", "pyproject.toml", "go.mod", "cargo.toml", "makefile");
    private static final Set<String> INFRA_FILES = Set.of(
            "dockerfile", "docker-compose.yml", "docker-compose.mysql.yml");

    private RepoMapOverview() {
    }

    static String render(IndexSnapshot snapshot, IndexScope scope) {
        List<IndexSnapshot.IndexedFile> files = snapshot.files().values().stream()
                .filter(file -> scope.matches(file.relativePath()))
                .toList();
        StringBuilder out = new StringBuilder();
        appendHeader(out, snapshot, files, scope);
        appendLanguages(out, files);
        appendDirectories(out, files, scope);
        appendKeyFiles(out, files, scope);
        out.append("\n提示：带 query 调用（例如 repo_map(query=\"审批流程\")）可获取相关文件排名。\n");
        return out.toString();
    }

    private static void appendHeader(StringBuilder out, IndexSnapshot snapshot,
            List<IndexSnapshot.IndexedFile> files, IndexScope scope) {
        out.append("仓库地图");
        if (!scope.prefix().isEmpty()) {
            out.append("（范围：").append(scope.prefix());
            if (!scope.singleFile()) {
                out.append('/');
            }
            out.append("）");
        }
        out.append('\n').append("已索引 ").append(files.size()).append(" 个文本文件");
        if (snapshot.indexedBytes() > 0) {
            out.append("，约 ").append(size(snapshot.indexedBytes()));
        }
        out.append("；跳过二进制 ").append(snapshot.skippedBinary())
                .append(" 个、超大文件 ").append(snapshot.skippedLarge()).append(" 个");
        if (snapshot.truncated()) {
            out.append("；索引达到上限，结果可能不完整");
        }
        out.append('\n');
    }

    private static void appendLanguages(StringBuilder out,
            List<IndexSnapshot.IndexedFile> files) {
        Map<String, Integer> counts = new HashMap<>();
        for (IndexSnapshot.IndexedFile file : files) {
            counts.merge(extension(file.relativePath()), 1, Integer::sum);
        }
        List<Map.Entry<String, Integer>> top = counts.entrySet().stream()
                .sorted((left, right) -> {
                    int byCount = Integer.compare(right.getValue(), left.getValue());
                    return byCount != 0 ? byCount : left.getKey().compareTo(right.getKey());
                })
                .limit(MAX_LANGUAGES)
                .toList();
        if (top.isEmpty()) {
            return;
        }
        out.append("语言分布：");
        out.append(top.stream().map(entry -> entry.getKey() + " " + entry.getValue())
                .collect(Collectors.joining("、")));
        out.append('\n');
    }

    private static void appendDirectories(StringBuilder out,
            List<IndexSnapshot.IndexedFile> files, IndexScope scope) {
        Map<String, Integer> directories = new TreeMap<>();
        List<String> rootFiles = new ArrayList<>();
        for (IndexSnapshot.IndexedFile file : files) {
            String relative = scope.strip(file.relativePath());
            if (relative.isEmpty()) {
                continue;
            }
            int slash = relative.indexOf('/');
            if (slash < 0) {
                rootFiles.add(relative);
            } else {
                directories.merge(relative.substring(0, slash), 1, Integer::sum);
            }
        }
        if (directories.isEmpty() && rootFiles.isEmpty()) {
            return;
        }
        out.append("目录结构：\n");
        directories.entrySet().stream()
                .sorted((left, right) -> {
                    int byCount = Integer.compare(right.getValue(), left.getValue());
                    return byCount != 0 ? byCount : left.getKey().compareTo(right.getKey());
                })
                .limit(MAX_DIRECTORIES)
                .forEach(entry -> out.append("- ").append(entry.getKey()).append("/（")
                        .append(entry.getValue()).append(" 个文件）\n"));
        if (directories.size() > MAX_DIRECTORIES) {
            out.append("- …其余目录省略\n");
        }
        if (!rootFiles.isEmpty()) {
            rootFiles.sort(String::compareTo);
            out.append("- 根目录文件：")
                    .append(rootFiles.stream().limit(MAX_ROOT_FILES)
                            .collect(Collectors.joining("、")));
            if (rootFiles.size() > MAX_ROOT_FILES) {
                out.append(" 等");
            }
            out.append('\n');
        }
    }

    private static void appendKeyFiles(StringBuilder out,
            List<IndexSnapshot.IndexedFile> files, IndexScope scope) {
        List<KeyFile> keys = new ArrayList<>();
        for (IndexSnapshot.IndexedFile file : files) {
            String relative = scope.strip(file.relativePath());
            if (relative.isEmpty()) {
                continue;
            }
            String name = fileName(relative).toLowerCase(Locale.ROOT);
            int priority = priority(name, relative);
            if (priority >= 0) {
                keys.add(new KeyFile(file.relativePath(), priority, reason(name, relative)));
            }
        }
        if (keys.isEmpty()) {
            return;
        }
        keys.sort((left, right) -> {
            int byPriority = Integer.compare(left.priority(), right.priority());
            return byPriority != 0 ? byPriority : left.path().compareTo(right.path());
        });
        out.append("\n关键文件：\n");
        keys.stream().limit(MAX_KEY_FILES)
                .forEach(key -> out.append("- ").append(key.path())
                        .append("（").append(key.reason()).append("）\n"));
    }

    private static int priority(String name, String relative) {
        if (DOC_FILES.contains(name)) {
            return 0;
        }
        if (BUILD_FILES.contains(name)) {
            return 1;
        }
        if (isEntryPoint(name, relative)) {
            return 2;
        }
        if (INFRA_FILES.contains(name)) {
            return 3;
        }
        return relative.contains("/db/migration/") ? 4 : -1;
    }

    private static String reason(String name, String relative) {
        if (DOC_FILES.contains(name)) {
            return "说明与约定";
        }
        if (BUILD_FILES.contains(name)) {
            return "构建与依赖";
        }
        if (isEntryPoint(name, relative)) {
            return "可能的入口";
        }
        if (INFRA_FILES.contains(name)) {
            return "运行与部署";
        }
        return "数据库迁移";
    }

    private static boolean isEntryPoint(String name, String relative) {
        return relative.endsWith("Application.java")
                || name.equals("main.java")
                || name.equals("main.go")
                || name.equals("manage.py")
                || name.equals("__main__.py")
                || name.equals("app.py")
                || name.equals("server.js")
                || name.equals("index.js")
                || name.equals("index.ts");
    }

    private static String extension(String relativePath) {
        String name = fileName(relativePath).toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? "无扩展名" : name.substring(dot + 1);
    }

    private static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static String size(long bytes) {
        if (bytes >= 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0);
        }
        if (bytes >= 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return bytes + " B";
    }

    private record KeyFile(String path, int priority, String reason) {
    }
}
