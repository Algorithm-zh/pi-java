package com.piagent.trigger.cli;

import com.piagent.domain.agent.RunEvent;
import java.io.PrintWriter;
import java.util.Map;

/**
 * TUI 的 run 事件渲染：正文流式输出，思考变暗，工具与压缩折叠成短行。
 *
 * <p>与行模式一样维护一条“未闭合的行”，在切换类型或消息结束时补换行，
 * 避免思考、正文和工具摘要粘在一起。</p>
 */
final class TuiRenderer implements RunRenderer {

    private static final int TOOL_ARG_LIMIT = 50;
    private static final int TOOL_OUTPUT_LIMIT = 240;
    private static final int NOTE_LIMIT = 160;

    private final PrintWriter out;
    private final Ansi ansi;
    private final StringBuilder current = new StringBuilder();
    private String finalText = "";
    private String openKind;

    TuiRenderer(PrintWriter out, Ansi ansi) {
        this.out = out;
        this.ansi = ansi;
    }

    @Override
    public void render(RunEvent event) {
        switch (event.type()) {
            case MESSAGE_STARTED -> {
                finishLine();
                current.setLength(0);
            }
            case MESSAGE_DELTA -> renderDelta(event.data());
            case MESSAGE_COMPLETED -> {
                finishLine();
                if (!current.isEmpty()) {
                    finalText = current.toString();
                }
            }
            case TOOL_STARTED -> toolStarted(event.data());
            case TOOL_COMPLETED -> toolCompleted(event.data());
            case COMPACTION_STARTED -> compactionStarted(event.data());
            case COMPACTION_COMPLETED -> compactionCompleted(event.data());
            case APPROVAL_RESOLVED -> approvalResolved(event.data());
            case REPLAY_GAP -> replayGap();
            case RUN_FAILED -> failed(event.data());
            default -> {
            }
        }
    }

    @Override
    public void finish() {
        finishLine();
        out.flush();
    }

    @Override
    public String finalText() {
        return finalText;
    }

    private void renderDelta(Map<String, Object> data) {
        String kind = text(data, "kind");
        String value = text(data, kind);
        if (value.isEmpty()) {
            return;
        }
        if ("text".equals(kind)) {
            open(kind, ansi.cyan("● "));
            current.append(value);
            out.print(value);
        } else if ("thinking".equals(kind)) {
            open(kind, ansi.dim("· 思考 "));
            out.print(ansi.dim(value));
        } else {
            return;
        }
        out.flush();
    }

    private void open(String kind, String prefix) {
        if (!kind.equals(openKind)) {
            finishLine();
            openKind = kind;
            out.print(prefix);
        }
    }

    private void finishLine() {
        if (openKind != null) {
            out.println();
        }
        openKind = null;
    }

    private void toolStarted(Map<String, Object> data) {
        finishLine();
        String arguments = Ansi.truncate(
                CliText.oneLine(text(data, "arguments"), 200), TOOL_ARG_LIMIT);
        out.println("  " + ansi.green("● ") + ansi.bold(text(data, "toolName"))
                + (arguments.isBlank() ? "" : " " + ansi.dim(arguments)));
        out.flush();
    }

    private void toolCompleted(Map<String, Object> data) {
        finishLine();
        boolean error = Boolean.TRUE.equals(data.get("isError"));
        String content = CliText.oneLine(text(data, "content"), TOOL_OUTPUT_LIMIT);
        String mark = error ? ansi.red("└ 失败") : ansi.dim("└ 完成");
        out.println("    " + mark + (content.isBlank() ? "" : " " + ansi.dim(content)));
        out.flush();
    }

    private void compactionStarted(Map<String, Object> data) {
        finishLine();
        String reason = CliText.oneLine(text(data, "reason"), NOTE_LIMIT);
        out.println("  " + ansi.yellow("◆ 压缩") + ansi.dim(" " + text(data, "mode")
                + " · " + text(data, "tokensBefore") + " token"
                + (reason.isBlank() ? "" : " · " + reason)));
        out.flush();
    }

    private void compactionCompleted(Map<String, Object> data) {
        finishLine();
        String note = CliText.oneLine(text(data, "note"), NOTE_LIMIT);
        out.println("    " + ansi.dim("└ " + text(data, "tokensBefore") + " → "
                + text(data, "tokensAfter") + " token"
                + (Boolean.TRUE.equals(data.get("prunedOnly")) ? "（仅剪枝）" : "")
                + (note.isBlank() ? "" : " · " + note)));
        out.flush();
    }

    private void approvalResolved(Map<String, Object> data) {
        finishLine();
        boolean approved = "APPROVED".equals(text(data, "status"));
        String reason = CliText.oneLine(text(data, "reason"), NOTE_LIMIT);
        String mark = approved ? ansi.green("√ 已批准") : ansi.red("× 已拒绝");
        out.println("    " + mark + " " + ansi.dim(text(data, "toolName")
                + (reason.isBlank() ? "" : " · " + reason)));
        out.flush();
    }

    private void replayGap() {
        finishLine();
        out.println("  " + ansi.yellow("! 部分早期事件已被清理，本次输出可能不完整"));
        out.flush();
    }

    private void failed(Map<String, Object> data) {
        finishLine();
        out.println("  " + ansi.red("× 失败: " + text(data, "errorMessage")));
        out.flush();
    }

    private static String text(Map<String, Object> data, String key) {
        Object value = data.get(key);
        return value == null ? "" : String.valueOf(value);
    }
}
