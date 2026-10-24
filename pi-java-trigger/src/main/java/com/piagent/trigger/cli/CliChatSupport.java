package com.piagent.trigger.cli;

import com.piagent.application.context.CompactionApplicationService;
import com.piagent.application.context.ManualCompactionView;
import com.piagent.application.session.command.ChangeSessionModelCommand;
import com.piagent.application.session.service.SessionSettingsApplicationService;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.SessionEntry;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * chat 的内置斜杠命令，供行模式与 TUI 共用。
 *
 * <p>只负责展示与调用应用层用例；具体界面怎么排版、怎么读输入由调用方决定。</p>
 */
@Component
public class CliChatSupport {

    private static final Set<String> COMMANDS = Set.of(
            "/help", "/exit", "/quit", "/abort", "/compact", "/model", "/tree", "/session");

    private final CliSessionService sessions;
    private final SessionSettingsApplicationService settings;
    private final CompactionApplicationService compaction;

    /**
     * 创建命令支持。
     *
     * @param sessions 会话准备
     * @param settings 会话设置
     * @param compaction 手动压缩
     */
    public CliChatSupport(CliSessionService sessions, SessionSettingsApplicationService settings,
            CompactionApplicationService compaction) {
        this.sessions = sessions;
        this.settings = settings;
        this.compaction = compaction;
    }

    /**
     * 判断输入是否为内置命令；{@code /skill:}、{@code /write} 等按普通消息发送。
     *
     * @param input 已去空白的输入
     * @return true 表示内置命令
     */
    static boolean isCommand(String input) {
        return COMMANDS.contains(input.split("\\s+", 2)[0]);
    }

    /**
     * 执行一条内置命令。
     *
     * @param session 当前会话
     * @param input 已去空白的输入
     * @param terminal 输出终端
     * @return 执行后的会话；切换模型时返回新实例
     */
    public ChatSession handle(ChatSession session, String input, CliTerminal terminal) {
        String[] parts = input.split("\\s+", 2);
        String command = parts[0];
        String argument = parts.length > 1 ? parts[1].strip() : "";
        switch (command) {
            case "/help" -> terminal.println(helpText());
            case "/abort" -> terminal.println("[提示] 当前没有运行中的任务");
            case "/compact" -> compact(session, terminal);
            case "/model" -> {
                return changeModel(session, argument, terminal);
            }
            case "/tree" -> printTree(session, terminal);
            case "/session" -> printSession(session, terminal);
            default -> terminal.println("未知命令: " + command + "，输入 /help 查看帮助");
        }
        return session;
    }

    private void compact(ChatSession session, CliTerminal terminal) {
        ManualCompactionView view = compaction.compact(session.identity().id());
        terminal.println(view.message());
        if (view.summary() != null) {
            terminal.println(CliText.oneLine(view.summary(), 200));
        }
    }

    private ChatSession changeModel(ChatSession session, String modelId, CliTerminal terminal) {
        if (modelId.isBlank()) {
            terminal.println("当前模型: " + session.config().modelProfileId()
                    + " · 思考 " + session.config().thinkingLevel());
            return session;
        }
        ChatSession updated = settings.changeModel(new ChangeSessionModelCommand(
                session.identity().id(), modelId, null));
        terminal.println("已切换模型: " + updated.config().modelProfileId()
                + " · 思考 " + updated.config().thinkingLevel());
        return updated;
    }

    private void printTree(ChatSession session, CliTerminal terminal) {
        List<SessionEntry> entries = sessions.branch(session.identity().id());
        if (entries.isEmpty()) {
            terminal.println("（空会话）");
            return;
        }
        for (SessionEntry entry : entries) {
            terminal.println("#" + entry.seq() + "  " + CliProjection.summary(entry));
        }
    }

    private void printSession(ChatSession session, CliTerminal terminal) {
        ChatSession current = sessions.require(session.identity().id().toString());
        terminal.println("会话: " + current.identity().id());
        terminal.println("标题: " + current.title());
        terminal.println("状态: " + current.status() + " · 模型 " + current.config().modelProfileId()
                + " · 思考 " + current.config().thinkingLevel());
        terminal.println("目录: " + current.config().cwd());
    }

    private static String helpText() {
        return """
                命令:
                  /help          显示帮助
                  /exit, /quit   退出
                  /abort         取消运行中的任务（运行期间输入有效）
                  /compact       手动压缩当前上下文
                  /model [ID]    查看或切换模型
                  /tree          查看当前分支 entry
                  /session       查看会话信息
                  /skill:名称    以技能方式发送消息""";
    }
}
