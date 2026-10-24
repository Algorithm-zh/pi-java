package com.piagent.trigger.cli;

import com.piagent.application.context.CompactionApplicationService;
import com.piagent.application.context.ManualCompactionView;
import com.piagent.application.session.command.ChangeSessionModelCommand;
import com.piagent.application.session.service.SessionSettingsApplicationService;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.ThinkingLevel;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * 交互式 REPL：同一进程内多轮对话。
 *
 * <p>run 执行期间主线程轮询事件队列，并在空闲时读取 {@code /abort}；
 * 审批提问也由主线程完成，保证终端的读写始终只有一个线程。</p>
 */
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
@Command(name = "chat", mixinStandardHelpOptions = true,
        description = "进入交互式对话。")
public final class ChatCommand implements Callable<Integer> {

    private static final Duration TURN_TIMEOUT = Duration.ofHours(1);

    @Option(names = "--cwd", paramLabel = "PATH", description = "工作目录，默认当前目录。")
    private String cwd;

    @Option(names = "--model", paramLabel = "ID", description = "模型档案；新会话默认第一个启用档案。")
    private String model;

    @Option(names = "--thinking", paramLabel = "LEVEL", description = "OFF/LOW/MEDIUM/HIGH，默认 OFF。")
    private String thinking;

    @Option(names = "--title", paramLabel = "TITLE", description = "新会话标题。")
    private String title;

    @Option(names = "--session", paramLabel = "ID", description = "继续已有会话。")
    private String sessionId;

    @Spec
    private CommandSpec spec;

    private final CliSessionService sessionService;
    private final CliRunService runService;
    private final CliRunExecutor executor;
    private final SessionSettingsApplicationService settings;
    private final CompactionApplicationService compaction;

    /**
     * 创建 chat 命令。
     *
     * @param sessionService 会话准备
     * @param runService run 启动
     * @param executor 事件消费
     * @param settings 会话设置
     * @param compaction 手动压缩
     */
    public ChatCommand(CliSessionService sessionService, CliRunService runService,
            CliRunExecutor executor, SessionSettingsApplicationService settings,
            CompactionApplicationService compaction) {
        this.sessionService = sessionService;
        this.runService = runService;
        this.executor = executor;
        this.settings = settings;
        this.compaction = compaction;
    }

    @Override
    public Integer call() {
        CliTerminal terminal = CliTerminal.of(spec.commandLine());
        ChatSession session = resolveSession(terminal);
        terminal.println("[会话] " + session.identity().id() + " · "
                + session.config().modelProfileId() + " · " + session.config().cwd());
        terminal.println("输入 /help 查看命令，/exit 退出。");
        while (true) {
            String line = terminal.readLine("你> ");
            if (line == null) {
                terminal.println("");
                break;
            }
            String input = line.strip();
            if (input.isEmpty()) {
                continue;
            }
            if ("/exit".equals(input) || "/quit".equals(input)) {
                break;
            }
            if (input.startsWith("/") && !input.startsWith("/skill:")) {
                ChatSession changed = handleCommand(session, input, terminal);
                if (changed != null) {
                    session = changed;
                }
                continue;
            }
            int code = runTurn(session, input, terminal);
            if (code != 0) {
                terminal.error("[提示] 上一次 run 退出码 " + code + "，可继续对话");
            }
        }
        return 0;
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

    private ChatSession handleCommand(ChatSession session, String input, CliTerminal terminal) {
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
        return null;
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
            return null;
        }
        ChatSession updated = settings.changeModel(new ChangeSessionModelCommand(
                session.identity().id(), modelId, null));
        terminal.println("已切换模型: " + updated.config().modelProfileId()
                + " · 思考 " + updated.config().thinkingLevel());
        return updated;
    }

    private void printTree(ChatSession session, CliTerminal terminal) {
        List<SessionEntry> entries = sessionService.branch(session.identity().id());
        if (entries.isEmpty()) {
            terminal.println("（空会话）");
            return;
        }
        for (SessionEntry entry : entries) {
            terminal.println("#" + entry.seq() + "  " + CliProjection.summary(entry));
        }
    }

    private void printSession(ChatSession session, CliTerminal terminal) {
        ChatSession current = sessionService.require(session.identity().id().toString());
        terminal.println("会话: " + current.identity().id());
        terminal.println("标题: " + current.title());
        terminal.println("状态: " + current.status() + " · 模型 " + current.config().modelProfileId()
                + " · 思考 " + current.config().thinkingLevel());
        terminal.println("目录: " + current.config().cwd());
    }

    private int runTurn(ChatSession session, String text, CliTerminal terminal) {
        SessionId id = session.identity().id();
        CliRun run = runService.start(id, text);
        try {
            RunOptions options = new RunOptions(false, false, TURN_TIMEOUT,
                    active -> checkAbort(active, terminal));
            return executor.execute(run, terminal, options);
        } finally {
            run.close();
        }
    }

    private void checkAbort(CliRun run, CliTerminal terminal) {
        String line = terminal.readReadyLine();
        if (line == null) {
            return;
        }
        if ("/abort".equals(line.strip())) {
            run.abort();
            terminal.println("[取消] 已请求停止");
        } else if (!line.isBlank()) {
            terminal.println("运行中，输入 /abort 取消");
        }
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
