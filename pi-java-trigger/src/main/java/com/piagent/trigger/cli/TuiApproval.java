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
            String risk = text(data, "riskLevel");
            String title = " 需要你确认 ";
            int boxWidth = 2 + Ansi.width(title) + 28;
            out.println();
            out.println("  " + ansi.amber("╭─") + ansi.dim(title + "─".repeat(28)));
            out.println(ansi.dim("  │ ") + ansi.bold(text(data, "toolName"))
                    + ansi.dim("  ·  ") + riskMark(risk));
            out.println(ansi.dim("  │ ") + "它准备这样做");
            out.println(ansi.dim("  │ ") + CliText.oneLine(text(data, "arguments"), ARG_LIMIT));
            out.println("  " + ansi.dim("╰" + "─".repeat(boxWidth - 1)));
            out.println("  " + ansi.bold("允许执行？")
                    + ansi.dim("  y = 允许 · 直接回车 = 拒绝"));
            out.flush();
            String line = reader.readLine(ansi.accent("  │› ")).strip();
            return isApprove(line) ? Answer.approve() : Answer.deny();
        } catch (UserInterruptException | EndOfFileException stop) {
            out.println(ansi.dim("  未输入，按拒绝处理"));
            out.flush();
            return Answer.missingInput();
        } finally {
            interrupts.resume();
        }
    }

    private String riskMark(String risk) {
        String label = CliText.riskLabel(risk);
        return "HIGH".equals(risk) || "CRITICAL".equals(risk)
                ? ansi.red(label) : ansi.amber(label);
    }

    private static boolean isApprove(String line) {
        return "y".equalsIgnoreCase(line) || "yes".equalsIgnoreCase(line)
                || "是".equals(line) || "好".equals(line);
    }

    private static String text(Map<String, Object> data, String key) {
        Object value = data.get(key);
        return value == null ? "" : String.valueOf(value);
    }
}
