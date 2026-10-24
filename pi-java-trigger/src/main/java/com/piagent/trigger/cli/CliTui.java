package com.piagent.trigger.cli;

import com.piagent.domain.session.model.ChatSession;
import java.io.IOException;
import java.time.Duration;
import java.util.OptionalInt;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.springframework.stereotype.Component;

/**
 * chat 的交互式界面：方框输入区、彩色流式输出、Tab 补全与 Ctrl+C 取消。
 *
 * <p>只有标准输入是真实终端时才启用；管道、重定向与测试环境会返回 empty，
 * 由调用方退回 {@link LineChat}，保证脚本行为不变。</p>
 */
@Component
public class CliTui {

    private static final Duration TURN_TIMEOUT = Duration.ofHours(1);
    private static final long DOUBLE_INTERRUPT_NANOS = 1_500_000_000L;
    private static final int MIN_WIDTH = 40;
    private static final int MAX_WIDTH = 120;

    private final CliChatSupport support;
    private final CliRunService runService;
    private final CliRunExecutor executor;

    /**
     * 创建 TUI。
     *
     * @param support 斜杠命令
     * @param runService run 启动
     * @param executor 事件消费
     */
    public CliTui(CliChatSupport support, CliRunService runService, CliRunExecutor executor) {
        this.support = support;
        this.runService = runService;
        this.executor = executor;
    }

    /**
     * 在支持 TTY 时进入交互界面。
     *
     * @param initial 初始会话
     * @return 退出码；空值表示当前环境不支持 TUI
     */
    public OptionalInt run(ChatSession initial) {
        Terminal terminal = openTerminal();
        if (terminal == null) {
            return OptionalInt.empty();
        }
        try {
            return OptionalInt.of(loop(initial, terminal));
        } finally {
            close(terminal);
        }
    }

    private Terminal openTerminal() {
        if (!CliTerminal.standardInput()) {
            return null;
        }
        try {
            Terminal terminal = TerminalBuilder.builder()
                    .system(true)
                    .dumb(true)
                    .encoding(CliCharset.resolve())
                    .build();
            if (isDumb(terminal) || terminal.getWidth() <= 0) {
                close(terminal);
                return null;
            }
            return terminal;
        } catch (IOException | RuntimeException error) {
            return null;
        }
    }

    private static boolean isDumb(Terminal terminal) {
        return Terminal.TYPE_DUMB.equals(terminal.getType())
                || Terminal.TYPE_DUMB_COLOR.equals(terminal.getType());
    }

    private int loop(ChatSession initial, Terminal terminal) {
        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .appName("pi-java")
                .history(new DefaultHistory())
                .completer(new SlashCompleter())
                .highlighter(new SlashHighlighter())
                .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
                .build();
        CliTerminal cli = CliTerminal.forTui(terminal.writer());
        Ansi ansi = new Ansi(Ansi.colorCapable());
        printBanner(initial, cli, ansi, terminal);
        ChatSession session = initial;
        InterruptClock clock = new InterruptClock();
        while (true) {
            String line = prompt(reader, cli, ansi, terminal, clock);
            if (line == null) {
                return 0;
            }
            String input = line.strip();
            if (input.isEmpty()) {
                continue;
            }
            if ("/exit".equals(input) || "/quit".equals(input)) {
                return 0;
            }
            if (CliChatSupport.isCommand(input)) {
                session = support.handle(session, input, cli);
                continue;
            }
            int code = runTurn(session, input, cli, terminal, reader, ansi);
            if (code != 0) {
                cli.println(ansi.dim("  [提示] 上一次 run 退出码 " + code + "，可继续对话"));
            }
        }
    }

    private static String prompt(LineReader reader, CliTerminal cli, Ansi ansi,
            Terminal terminal, InterruptClock clock) {
        try {
            cli.println(" ");
            cli.out().print(composerTop(ansi, terminal));
            cli.out().flush();
            String line = reader.readLine(composerPrompt(ansi));
            cli.println(ansi.dim(composerBottom(terminal)));
            clock.last = 0;
            return line;
        } catch (UserInterruptException interrupted) {
            cli.println(ansi.dim(composerBottom(terminal)));
            long now = System.nanoTime();
            if (clock.last != 0 && now - clock.last <= DOUBLE_INTERRUPT_NANOS) {
                return null;
            }
            clock.last = now;
            cli.println(ansi.dim("  再按一次 Ctrl+C 退出"));
            return "";
        } catch (EndOfFileException eof) {
            cli.println("");
            return null;
        }
    }

    private int runTurn(ChatSession session, String text, CliTerminal cli,
            Terminal terminal, LineReader reader, Ansi ansi) {
        CliRun run = runService.start(session.identity().id(), text);
        TuiInterrupts interrupts = TuiInterrupts.start(terminal, run, cli.out());
        try {
            RunOptions options = new RunOptions(false, false, TURN_TIMEOUT, null,
                    new TuiRenderer(cli.out(), ansi),
                    new TuiApproval(cli.out(), reader, ansi, interrupts));
            return executor.execute(run, cli, options);
        } finally {
            interrupts.close();
            run.close();
        }
    }

    private static void printBanner(ChatSession session, CliTerminal cli, Ansi ansi,
            Terminal terminal) {
        int width = width(terminal);
        cli.println(" ");
        cli.println("  " + ansi.accent("π") + "  " + ansi.bold("pi-java")
                + ansi.dim("  ·  " + session.config().modelProfileId()));
        cli.println(ansi.dim("  " + "─".repeat(Math.min(width - 4, 46))));
        cli.println("  " + Ansi.truncate("读代码、跑命令、改文件。需要动手时，我会先问你。",
                width - 4));
        cli.println(ansi.dim("  " + Ansi.truncate(
                String.valueOf(session.config().cwd()), width - 4)));
        cli.println(ansi.dim("  " + Ansi.truncate("会话 " + session.identity().id()
                + " · /help 命令 · Tab 补全 · Ctrl+C 取消 · Ctrl+D 退出", width - 4)));
        cli.println(" ");
    }

    private static String composerTop(Ansi ansi, Terminal terminal) {
        int box = Math.max(20, width(terminal) - 4);
        String label = " 想做什么？ ";
        int filler = Math.max(2, box - 2 - Ansi.width(label));
        return "  " + ansi.accent("╭─") + ansi.dim(label + "─".repeat(filler)) + "\n";
    }

    private static String composerPrompt(Ansi ansi) {
        return "  " + ansi.accent("│› ");
    }

    private static String composerBottom(Terminal terminal) {
        return "  ╰" + "─".repeat(Math.max(10, width(terminal) - 5));
    }

    private static int width(Terminal terminal) {
        return Math.min(Math.max(terminal.getWidth(), MIN_WIDTH), MAX_WIDTH);
    }

    private static void close(Terminal terminal) {
        try {
            terminal.close();
        } catch (IOException error) {
            // 终端关闭失败没有可恢复的动作
        }
    }

    private static final class InterruptClock {
        private long last;
    }
}
