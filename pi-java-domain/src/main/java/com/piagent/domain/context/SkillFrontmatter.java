package com.piagent.domain.context;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SKILL.md 的 frontmatter 与正文拆分。
 *
 * <p>为什么放在领域层：frontmatter 是 Skill 的<b>规范</b>（哪些字段必填、name 是否
 * 合规、description 是否截断），而不是某个文件系统的细节；把解析规则放在领域里，
 * 基础设施只负责读文件、拼路径与缓存，测试也可以脱离磁盘直接验证规则。</p>
 *
 * <p>只支持极简 YAML 子集（刻意不引入 YAML 库，符合“简洁化”原则）：</p>
 * <pre>
 * ---
 * name: code-review
 * description: 按团队规范审查改动
 * ---
 * 正文 Markdown
 * </pre>
 *
 * <p>支持：顶层 {@code key: value}、单行值、引号值、注释行；不支持嵌套、数组与多行块。
 * 复杂 frontmatter 会被直接忽略而不是报错——Skill 的价值在正文，扫描不应因一个
 * 不支持的字段而整体失败。</p>
 *
 * <p>纯函数、无状态。</p>
 */
public final class SkillFrontmatter {

    /** 分隔线。 */
    public static final String DELIMITER = "---";

    /** name 允许的最大长度。 */
    public static final int MAX_NAME_LENGTH = 64;

    /** description 超过该长度时截断。 */
    public static final int MAX_DESCRIPTION_LENGTH = 512;

    private static final String FALLBACK_DESCRIPTION = "（未提供 description）";

    private SkillFrontmatter() {
    }

    /**
     * 解析 SKILL.md 全文。
     *
     * @param content 文件全文
     * @param fallbackName 文件名所在目录名，frontmatter 缺少 name 时使用
     * @return 解析结果
     */
    public static Parsed parse(String content, String fallbackName) {
        String text = content == null ? "" : content;
        List<String> lines = text.lines().toList();
        int end = frontmatterEnd(lines);
        Map<String, String> fields = end < 0 ? Map.of() : fields(lines.subList(1, end));
        String body = end < 0 ? text : String.join("\n", lines.subList(end + 1, lines.size()));
        String name = normalizeName(fields.getOrDefault("name", fallbackName));
        String description = normalizeDescription(fields.get("description"), body);
        return new Parsed(name, description, body.strip());
    }

    /**
     * 返回 frontmatter 结束行下标（即第二个分隔线所在行）；没有 frontmatter 返回 -1。
     */
    private static int frontmatterEnd(List<String> lines) {
        if (lines.isEmpty() || !isDelimiter(lines.get(0))) {
            return -1;
        }
        for (int index = 1; index < lines.size(); index++) {
            if (isDelimiter(lines.get(index))) {
                return index;
            }
        }
        return -1;
    }

    private static boolean isDelimiter(String line) {
        return DELIMITER.equals(line.strip());
    }

    private static Map<String, String> fields(List<String> lines) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("-")) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = line.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            String value = unquote(line.substring(colon + 1).strip());
            if (!value.isEmpty()) {
                fields.putIfAbsent(key, value);
            }
        }
        return fields;
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            boolean doubleQuoted = value.startsWith("\"") && value.endsWith("\"");
            boolean singleQuoted = value.startsWith("'") && value.endsWith("'");
            if (doubleQuoted || singleQuoted) {
                return value.substring(1, value.length() - 1).strip();
            }
        }
        return value;
    }

    /**
     * 规范化名称：只保留小写字母、数字、连字符与下划线，其余替换为连字符。
     *
     * <p>规范化而不是拒绝，是因为目录名常带大写或空格；规范化后仍为空才视为非法。</p>
     */
    static String normalizeName(String raw) {
        String value = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        value = value.replaceAll("[^a-z0-9-]+", "-").replaceAll("^-+|-+$", "");
        Guard.require(!value.isBlank(), DomainErrorCode.INVALID_ARGUMENT, "skill name 不能为空");
        Guard.require(value.length() <= MAX_NAME_LENGTH, DomainErrorCode.INVALID_ARGUMENT,
                "skill name 不能超过 " + MAX_NAME_LENGTH + " 个字符");
        return value;
    }

    /**
     * 规范化描述：优先使用 frontmatter；缺失时取正文第一段非标题文本；仍然为空则占位。
     */
    static String normalizeDescription(String raw, String body) {
        String value = raw == null || raw.isBlank() ? firstParagraph(body) : raw;
        value = value.replace('\r', ' ').replace('\n', ' ').replaceAll("\\s+", " ").strip();
        if (value.isEmpty()) {
            return FALLBACK_DESCRIPTION;
        }
        return value.length() <= MAX_DESCRIPTION_LENGTH
                ? value : value.substring(0, MAX_DESCRIPTION_LENGTH).strip() + "…";
    }

    /**
     * 取正文第一段可读文字：跳过标题，遇到空行即结束。
     */
    private static String firstParagraph(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (String line : body.lines().toList()) {
            String stripped = line.strip();
            if (stripped.isEmpty()) {
                if (!text.isEmpty()) {
                    break;
                }
                continue;
            }
            if (stripped.startsWith("#")) {
                if (!text.isEmpty()) {
                    break;
                }
                continue;
            }
            text.append(stripped).append(' ');
        }
        return text.toString().strip();
    }

    /**
     * 解析结果。
     *
     * @param name 规范化后的名称
     * @param description 一行描述
     * @param body 去掉 frontmatter 的正文
     */
    public record Parsed(String name, String description, String body) {
    }
}
