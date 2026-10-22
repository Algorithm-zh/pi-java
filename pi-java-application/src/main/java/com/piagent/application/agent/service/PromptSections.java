package com.piagent.application.agent.service;

import com.piagent.domain.tool.model.ToolDescriptor;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * system prompt 各小节的组装器。
 *
 * <p>固定顺序（任何小节都有长度上限，总长度受 {@link #MAX_PROMPT_CHARS} 约束）：</p>
 * <ol>
 *   <li>身份与行为约束；</li>
 *   <li>环境：cwd、操作系统、思考级别；</li>
 *   <li>工具目录与风险摘要（不含 JSON Schema，schema 走工具参数）；</li>
 *   <li>Skills 目录：仅 name + description；</li>
 *   <li>AGENTS.md：工作区约定（≤32 KB，由基础设施截断）；</li>
 *   <li>显式注入的 skill 正文（{@code /skill:name}）。</li>
 * </ol>
 *
 * <p>刻意不包含时间戳：system prompt 参与前缀缓存，放入易变内容会让每次请求都失效；
 * 也会让测试难以稳定断言。</p>
 */
final class PromptSections {

    /** 整个 system prompt 的字符上限。 */
    static final int MAX_PROMPT_CHARS = 24_000;

    /** AGENTS.md 段落的上限。 */
    static final int MAX_AGENTS_CHARS = 16_000;

    /** 显式 skill 正文的上限。 */
    static final int MAX_SKILL_BODY_CHARS = 8_000;

    private static final String TRUNCATED = "\n…（内容已截断）";

    private PromptSections() {
    }

    /**
     * 身份与行为约束。
     *
     * @return 文本
     */
    static String identity() {
        return """
                你是 pi-java，一个运行在用户工作区中的编码代理。

                规则：
                1. 回答简洁、直接，默认使用中文，除非用户使用其他语言。
                2. 文件内容以工具结果为准；不要编造没有读取过的文件内容。
                3. 需要了解工作区时主动调用 read、ls、grep、glob，不要猜测。
                4. 修改文件前先读取；write/edit 可能触发人工审批，被拒绝时如实说明，不要反复重试。
                5. 工具失败时先阅读错误原因，再决定是否换一种方式继续。
                6. 长对话会被自动压缩为摘要；摘要里没有的信息请重新读取，不要臆测。
                """.strip();
    }

    /**
     * 环境小节。
     *
     * @param cwd 工作目录
     * @param thinkingLevel 思考级别
     * @return 文本
     */
    static String environment(String cwd, String thinkingLevel) {
        return "工作目录：" + cwd + "\n操作系统：" + System.getProperty("os.name", "unknown")
                + "\n当前思考级别：" + thinkingLevel;
    }

    /**
     * 工具目录小节。
     *
     * @param tools 本轮工具
     * @return 文本
     */
    static String tools(List<ToolDescriptor> tools) {
        if (tools.isEmpty()) {
            return "可用工具：（无）";
        }
        String lines = tools.stream()
                .sorted((left, right) -> left.name().toString().compareTo(right.name().toString()))
                .map(tool -> "- " + tool.name() + "（" + riskOf(tool) + "）: " + oneLine(tool.description()))
                .collect(Collectors.joining("\n"));
        return "可用工具：\n" + lines;
    }

    /**
     * Skills 目录小节。
     *
     * @param formatted 已格式化的目录文本
     * @return 文本；目录为空返回空字符串
     */
    static String skills(String formatted) {
        if (formatted == null || formatted.isBlank()) {
            return "";
        }
        return "可用 Skills（需要细节时用 read 读取对应 SKILL.md，或由用户使用 /skill:名称 注入）：\n"
                + formatted;
    }

    /**
     * AGENTS.md 小节。
     *
     * @param content 文档内容
     * @return 文本；为空返回空字符串
     */
    static String agents(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        return "工作区约定（AGENTS.md）：\n" + truncate(content, MAX_AGENTS_CHARS);
    }

    /**
     * 显式注入的 skill。
     *
     * @param name skill 名称
     * @param body 正文
     * @return 文本
     */
    static String skillBody(String name, String body) {
        return "技能 " + name + " 的完整说明：\n" + truncate(body, MAX_SKILL_BODY_CHARS);
    }

    /**
     * 合并小节并施加总长度上限。
     *
     * <p>合并顺序即优先级：总长度超限时从尾部裁剪，因此显式注入的 skill 正文
     * 最先被裁，身份、环境与工具目录始终保留。</p>
     *
     * @param sections 小节
     * @return 最终 prompt
     */
    static String join(List<String> sections) {
        StringBuilder prompt = new StringBuilder();
        for (String section : sections) {
            if (section == null || section.isBlank()) {
                continue;
            }
            if (!prompt.isEmpty()) {
                prompt.append("\n\n");
            }
            prompt.append(section.strip());
        }
        if (prompt.length() > MAX_PROMPT_CHARS) {
            return prompt.substring(0, MAX_PROMPT_CHARS) + TRUNCATED;
        }
        return prompt.toString();
    }

    private static String riskOf(ToolDescriptor tool) {
        return tool.risk().name().toLowerCase(Locale.ROOT);
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.replace('\r', ' ').replace('\n', ' ').strip();
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + TRUNCATED;
    }
}
