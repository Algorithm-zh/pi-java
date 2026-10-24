package com.piagent.trigger.cli;

import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.SessionId;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * chat 的行模式实现：逐行读取，适用于管道、重定向与不支持 TTY 的终端。
 *
 * <p>run 执行期间主线程轮询事件队列，并在空闲时读取 {@code /abort}；
 * 审批提问也由主线程完成，保证终端的读写始终只有一个线程。</p>
 */
@Component
public class LineChat {

    private static final Duration TURN_TIMEOUT = Duration.ofHours(1);

    private final CliRunService runService;
    private final CliRunExecutor executor;
    private final CliChatSupport support;

    /**
     * 创建行模式会话。
     *
     * @param runService run 启动
     * @param executor 事件消费
     * @param support 斜杠命令
     */
    public LineChat(CliRunService runService, CliRunExecutor executor, CliChatSupport support) {
        this.runService = runService;
        this.executor = executor;
        this.support = support;
    }

    /**
     * 进入多轮对话。
     *
     * @param initial 初始会话
     * @param terminal 终端
     * @return 退出码
     */
    public int run(ChatSession initial, CliTerminal terminal) {
        ChatSession session = initial;
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
            if (CliChatSupport.isCommand(input)) {
                session = support.handle(session, input, terminal);
                continue;
            }
            int code = runTurn(session, input, terminal);
            if (code != 0) {
                terminal.error("[提示] 上一次 run 退出码 " + code + "，可继续对话");
            }
        }
        return 0;
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
}
