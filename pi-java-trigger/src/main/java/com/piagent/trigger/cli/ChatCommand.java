package com.piagent.trigger.cli;

import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.ThinkingLevel;
import java.nio.file.Path;
import java.util.OptionalInt;
import java.util.concurrent.Callable;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * 交互式对话：真实终端使用方框式 TUI，管道与脚本退回行模式。
 *
 * <p>两种界面共享会话准备、斜杠命令与 run 执行器；本类只负责解析参数、
 * 创建或恢复会话，然后选择界面。</p>
 */
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
@Command(name = "chat", mixinStandardHelpOptions = true,
        description = "进入交互式对话。")
public final class ChatCommand implements Callable<Integer> {

    @Option(names = "--cwd", paramLabel = "PATH", description = "工作目录，支持 ~，默认当前目录。")
    private String cwd;

    @Option(names = "--model", paramLabel = "ID", description = "模型档案；新会话默认第一个启用档案。")
    private String model;

    @Option(names = "--thinking", paramLabel = "LEVEL", description = "OFF/LOW/MEDIUM/HIGH，默认 OFF。")
    private String thinking;

    @Option(names = "--title", paramLabel = "TITLE", description = "新会话标题。")
    private String title;

    @Option(names = "--session", paramLabel = "ID", description = "继续已有会话。")
    private String sessionId;

    @Option(names = "--plain", description = "强制行模式，不启用交互式界面。")
    private boolean plain;

    @Spec
    private CommandSpec spec;

    private final CliSessionService sessionService;
    private final LineChat lineChat;
    private final CliTui tui;

    /**
     * 创建 chat 命令。
     *
     * @param sessionService 会话准备
     * @param lineChat 行模式界面
     * @param tui 交互式界面
     */
    public ChatCommand(CliSessionService sessionService, LineChat lineChat, CliTui tui) {
        this.sessionService = sessionService;
        this.lineChat = lineChat;
        this.tui = tui;
    }

    @Override
    public Integer call() {
        CliTerminal terminal = CliTerminal.of(spec.commandLine());
        ChatSession session = resolveSession(terminal);
        if (!plain) {
            OptionalInt code = tui.run(session);
            if (code.isPresent()) {
                return code.getAsInt();
            }
        }
        return lineChat.run(session, terminal);
    }

    private ChatSession resolveSession(CliTerminal terminal) {
        if (sessionId != null && !sessionId.isBlank()) {
            if (model != null || thinking != null) {
                terminal.error("[提示] 已指定 --session，忽略 --model/--thinking，沿用会话配置");
            }
            return sessionService.require(sessionId);
        }
        Path directory = sessionService.cwd(cwd);
        String modelId = model == null || model.isBlank()
                ? sessionService.defaultModelId() : model.strip();
        ThinkingLevel level = sessionService.thinking(thinking);
        String titleText = title == null || title.isBlank() ? "CLI 会话" : title.strip();
        return sessionService.create(directory, modelId, level, titleText);
    }
}
