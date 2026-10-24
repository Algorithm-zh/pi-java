package com.piagent.trigger.cli;

import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.ThinkingLevel;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Callable;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * 单次非交互执行。
 *
 * <p>等价于“创建/复用会话 → 启动 run → 消费事件直到终态”，适合脚本与 CI。
 * 默认流式输出；{@code --json} 时 stdout 只有一个 JSON 对象。</p>
 */
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
@Command(name = "run", mixinStandardHelpOptions = true,
        description = "执行一次任务并等待结束。")
public final class RunCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", paramLabel = "MESSAGE",
            description = "任务文本；省略时从 stdin 读取到 EOF。")
    private String message;

    @Option(names = "--cwd", paramLabel = "PATH", description = "工作目录，支持 ~，默认当前目录。")
    private String cwd;

    @Option(names = "--model", paramLabel = "ID", description = "模型档案；新会话默认第一个启用档案。")
    private String model;

    @Option(names = "--thinking", paramLabel = "LEVEL", description = "OFF/LOW/MEDIUM/HIGH，默认 OFF。")
    private String thinking;

    @Option(names = "--title", paramLabel = "TITLE", description = "新会话标题。")
    private String title;

    @Option(names = "--session", paramLabel = "ID", description = "复用已有会话。")
    private String sessionId;

    @Option(names = "--json", description = "只向 stdout 输出最终 JSON。")
    private boolean json;

    @Option(names = "--timeout", paramLabel = "SECONDS", defaultValue = "600",
            description = "等待 run 结束的秒数，默认 600。")
    private long timeoutSeconds;

    @Option(names = "--approve", description = "自动批准本次 run 的全部审批（谨慎使用）。")
    private boolean approve;

    @Spec
    private CommandSpec spec;

    private final CliSessionService sessionService;
    private final CliRunService runService;
    private final CliRunExecutor executor;

    /**
     * 创建 run 命令。
     *
     * @param sessionService 会话准备
     * @param runService run 启动与订阅
     * @param executor 事件消费与结果输出
     */
    public RunCommand(CliSessionService sessionService, CliRunService runService,
            CliRunExecutor executor) {
        this.sessionService = sessionService;
        this.runService = runService;
        this.executor = executor;
    }

    @Override
    public Integer call() {
        CliTerminal terminal = CliTerminal.of(spec.commandLine());
        if (timeoutSeconds <= 0) {
            throw new DomainException(DomainErrorCode.INVALID_ARGUMENT, "--timeout 必须大于 0");
        }
        String text = resolveText(terminal);
        ChatSession session = resolveSession(terminal, text);
        PrintWriter info = json ? terminal.err() : terminal.out();
        info.println("[会话] " + session.identity().id() + " · "
                + session.config().modelProfileId() + " · " + session.config().cwd());
        info.flush();
        CliRun run = runService.start(session.identity().id(), text);
        try {
            RunOptions options = new RunOptions(json, approve,
                    Duration.ofSeconds(timeoutSeconds), null);
            return executor.execute(run, terminal, options);
        } finally {
            run.close();
        }
    }

    private String resolveText(CliTerminal terminal) {
        if (message != null && !message.isBlank()) {
            return message.strip();
        }
        String piped = terminal.readAll();
        if (piped.isBlank()) {
            throw new DomainException(DomainErrorCode.INVALID_ARGUMENT,
                    "缺少任务文本：请传入 MESSAGE 或通过 stdin 提供内容");
        }
        return piped.strip();
    }

    private ChatSession resolveSession(CliTerminal terminal, String text) {
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
        String titleText = title == null || title.isBlank()
                ? CliSessionService.defaultTitle(text) : title.strip();
        return sessionService.create(directory, modelId, level, titleText);
    }
}
