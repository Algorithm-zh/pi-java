package com.piagent.application.agent.service;

import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.tool.model.ToolDescriptor;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 最小 system prompt 组装器。
 *
 * <p>M4 只注入身份、工作区、只读工具与输出约束；Skills 目录、AGENTS.md 与插件
 * prompt section 分别在 M7/M5 接入。提示词不包含时间等易变信息，便于测试稳定。
 */
@Component
public class SystemPromptBuilder {

    /**
     * 组装 system prompt。
     *
     * @param session 会话
     * @param tools 本轮可用工具
     * @return 提示词
     */
    public String build(ChatSession session, List<ToolDescriptor> tools) {
        String toolNames = tools.stream()
                .map(tool -> tool.name().toString())
                .sorted()
                .collect(Collectors.joining(", "));
        return """
                你是 pi-java，一个运行在用户工作区中的编码代理。

                工作目录：%s
                可用工具：%s
                当前思考级别：%s

                规则：
                1. 回答简洁、直接，默认使用中文，除非用户使用其他语言。
                2. 文件内容以工具结果为准；不要编造没有读取过的文件内容。
                3. 需要了解工作区时主动调用 read、ls、grep、glob，不要猜测。
                4. v0.1 的工具是只读的，不能修改文件或执行命令；涉及修改时明确说明限制。
                5. 工具失败时先阅读错误原因，再决定是否换一种方式继续。
                """.formatted(session.config().cwd(), toolNames, session.config().thinkingLevel());
    }
}
