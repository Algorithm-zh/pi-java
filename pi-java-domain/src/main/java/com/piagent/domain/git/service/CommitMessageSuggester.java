package com.piagent.domain.git.service;

import com.piagent.domain.git.model.FileChange;
import java.util.List;
import java.util.Locale;

/**
 * 根据改动文件给出 Conventional Commits 风格的提交信息建议。
 *
 * <p>纯启发式，不调用 LLM：只让用户少打一行字，不替用户判断改动是否正确。
 * 建议只使用低风险的类型（docs/test/ci/build/chore），不自动声称 feat 或 fix。</p>
 */
public final class CommitMessageSuggester {

    private CommitMessageSuggester() {
    }

    /**
     * 生成提交信息建议。
     *
     * @param files 改动文件
     * @return 单行建议；没有改动时为空字符串
     */
    public static String suggest(List<FileChange> files) {
        if (files == null || files.isEmpty()) {
            return "";
        }
        String type = typeOf(files);
        if (files.size() == 1) {
            return type + ": 更新 " + files.get(0).path();
        }
        return type + ": 更新 " + files.size() + " 个文件";
    }

    private static String typeOf(List<FileChange> files) {
        if (files.stream().allMatch(CommitMessageSuggester::isDocumentation)) {
            return "docs";
        }
        if (files.stream().allMatch(CommitMessageSuggester::isTest)) {
            return "test";
        }
        if (files.stream().allMatch(CommitMessageSuggester::isCi)) {
            return "ci";
        }
        if (files.stream().anyMatch(CommitMessageSuggester::isBuild)) {
            return "build";
        }
        return "chore";
    }

    private static boolean isDocumentation(FileChange change) {
        String path = lower(change.path());
        return path.startsWith("docs/") || path.endsWith(".md") || path.endsWith(".mdx")
                || path.endsWith(".rst");
    }

    private static boolean isTest(FileChange change) {
        String path = lower(change.path());
        return path.contains("/test/") || path.contains("/tests/") || path.endsWith("test.java")
                || path.endsWith("tests.java") || path.endsWith(".test.js") || path.endsWith(".test.ts")
                || path.endsWith(".spec.js") || path.endsWith(".spec.ts");
    }

    private static boolean isCi(FileChange change) {
        String path = lower(change.path());
        return path.startsWith(".github/") || path.equals(".gitlab-ci.yml") || path.equals("jenkinsfile");
    }

    private static boolean isBuild(FileChange change) {
        String path = lower(change.path());
        return path.equals("pom.xml") || path.endsWith("/pom.xml") || path.equals("build.gradle")
                || path.equals("build.gradle.kts") || path.equals("settings.gradle")
                || path.endsWith("package.json") || path.endsWith("package-lock.json")
                || path.endsWith("pnpm-lock.yaml") || path.endsWith("yarn.lock");
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
