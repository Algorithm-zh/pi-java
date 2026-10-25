package com.piagent.infrastructure.git;

import com.piagent.domain.git.model.FileChange;
import com.piagent.domain.git.model.GitChangeKind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析 {@code git diff-tree -z} 输出。
 *
 * <p>所有记录以 NUL 分隔，路径中的空格、换行与引号都不需要额外反转义。</p>
 */
final class GitDiffParser {

    private GitDiffParser() {
    }

    /**
     * 解析 {@code --name-status -z} 输出。
     *
     * @param output 原始输出
     * @return 按路径去重的变化列表，顺序与 Git 输出一致
     */
    static List<FileChange> nameStatus(String output) {
        String[] tokens = tokens(output);
        List<FileChange> changes = new ArrayList<>();
        int index = 0;
        while (index + 1 < tokens.length) {
            String status = tokens[index++];
            String path = tokens[index++];
            if (status.startsWith("R") || status.startsWith("C")) {
                path = index < tokens.length ? tokens[index++] : path;
            }
            changes.add(new FileChange(path, kindOf(status), 0, 0, false));
        }
        return changes;
    }

    /**
     * 解析 {@code --numstat -z} 输出，返回路径到行数的映射。
     *
     * @param output 原始输出
     * @return 路径 → [新增, 删除, 是否二进制]
     */
    static Map<String, int[]> numstat(String output) {
        Map<String, int[]> stats = new LinkedHashMap<>();
        for (String record : output.split("\u0000")) {
            if (record.isBlank()) {
                continue;
            }
            String[] parts = record.split("\t", 3);
            if (parts.length < 3) {
                continue;
            }
            boolean binary = "-".equals(parts[0]) || "-".equals(parts[1]);
            stats.put(parts[2], new int[]{number(parts[0]), number(parts[1]), binary ? 1 : 0});
        }
        return stats;
    }

    /**
     * 合并 name-status 与 numstat。
     *
     * @param statusOutput name-status 输出
     * @param numstatOutput numstat 输出
     * @return 带行数的变化列表
     */
    static List<FileChange> merge(String statusOutput, String numstatOutput) {
        Map<String, int[]> stats = numstat(numstatOutput);
        List<FileChange> merged = new ArrayList<>();
        for (FileChange change : nameStatus(statusOutput)) {
            int[] row = stats.getOrDefault(change.path(), new int[3]);
            merged.add(new FileChange(change.path(), change.kind(), row[0], row[1], row[2] == 1));
        }
        return merged;
    }

    private static String[] tokens(String output) {
        String[] raw = output.split("\u0000");
        List<String> tokens = new ArrayList<>(raw.length);
        for (String token : raw) {
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
        return tokens.toArray(new String[0]);
    }

    private static GitChangeKind kindOf(String status) {
        if (status.isEmpty()) {
            return GitChangeKind.MODIFIED;
        }
        return switch (status.charAt(0)) {
            case 'A' -> GitChangeKind.ADDED;
            case 'D' -> GitChangeKind.DELETED;
            default -> GitChangeKind.MODIFIED;
        };
    }

    private static int number(String value) {
        if ("-".equals(value) || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException error) {
            return 0;
        }
    }
}
