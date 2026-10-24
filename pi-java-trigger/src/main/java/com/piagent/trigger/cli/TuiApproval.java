package com.piagent.trigger.cli;

import java.io.PrintWriter;
import java.util.Map;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;

/**
 * TUI 审批面板：画出工具与风险，读完一行回答后交回执行器。
 *
 * <p>读取期间暂停 run 的 Ctrl+C 监听，让 {@code LineReader} 自己把中断转换成
 * “没有输入”，从而按拒绝处理，而不是取消整个 run。</p>
 */
final class TuiApproval implements ApprovalPrompt {

    private static final int ARG_LIMIT = 300;

    private final PrintWriter out;
    private final LineReader reader;
    private final Ansi ansi;
    private final TuiInterrupts interrupts;

    TuiApproval(PrintWriter out, LineReader reader, Ansi ansi, TuiInterrupts interrupts) {
        this.out = out;
        this.reader = reader;
        this.ansi = ansi;
        this.interrupts = interrupts;
    }

    @Override
    public Answer ask(Map<String, Object> data) {
        interrupts.pause();
        try {
            out.println();
            out.println("  " + ansi.yellow("┌─ 需要审批 ") + ansi.dim("─".repeat(20)));
            out.println(ansi.dim("  │ ") + ansi.bold(text(data, "toolName"))
                    + ansi.dim(" · 风险 " + text(data, "riskLevel")));
            out.println(ansi.dim("  │ ") + CliText.oneLine(text(data, "arguments"), ARG_LIMIT));
            out.println("  " + ansi.dim("└ ") + "批准执行? " + ansi.dim("[y/N]"));
            out.flush();
            String line = reader.readLine(ansi.cyan("  │ ")).strip();
            if (line.isEmpty()) {
                return Answer.deny();
            }
            return "y".equalsIgnoreCase(line) || "yes".equalsIgnoreCase(line)
                    ? Answer.approve() : Answer.deny();
        } catch (UserInterruptException | EndOfFileException stop) {
            out.println(ansi.dim("  未输入，按拒绝处理"));
            out.flush();
            return Answer.missingInput();
        } finally {
            interrupts.resume();
        }
    }

    private static String text(Map<String, Object> data, String key) {
        Object value = data.get(key);
        return value == null ? "" : String.valueOf(value);
    }
}
