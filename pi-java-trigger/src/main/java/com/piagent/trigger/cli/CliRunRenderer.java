package com.piagent.trigger.cli;

import com.piagent.domain.agent.RunEvent;
import java.util.Map;

/**
 * 把 run 事件渲染为终端文本，或在 JSON 模式下只收集最终回答。
 *
 * <p>文本与思考增量共用一个“打开的行”状态：切换类型或消息结束时补换行，
 * 保证流式输出不会把工具摘要和正文粘在一起。</p>
 */
public final class CliRunRenderer implements RunRenderer {

    private static final int TOOL_ARG_LIMIT = 160;
    private static final int TOOL_OUTPUT_LIMIT = 240;
    private static final int COMPACTION_TEXT_LIMIT = 160;

    private final CliTerminal terminal;
    private final boolean json;
    private final StringBuilder current = new StringBuilder();
    private String finalText = "";
    private String openKind;

    /**
     * 创建渲染器。
     *
     * @param terminal 终端
     * @param json 是否 JSON 模式
     */
    public CliRunRenderer(CliTerminal terminal, boolean json) {
        this.terminal = terminal;
        this.json = json;
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
    }

    @Override
    public String finalText() {
        return finalText;
    }

    private void renderDelta(Map<String, Object> data) {
        String kind = text(data, "kind");
        if ("text".equals(kind)) {
            String value = text(data, "text");
            current.append(value);
            if (!json) {
                switchMode(kind);
                terminal.out().print(value);
                terminal.out().flush();
            }
        } else if ("thinking".equals(kind) && !json) {
            switchMode(kind);
            terminal.out().print(text(data, "thinking"));
            terminal.out().flush();
        }
    }

    private void switchMode(String kind) {
        if (!kind.equals(openKind)) {
            finishLine();
        }
        if (openKind == null) {
            openKind = kind;
            if ("thinking".equals(kind)) {
                terminal.out().print("[思考] ");
            }
        }
    }

    private void finishLine() {
        if (openKind != null && !json) {
            terminal.println("");
        }
        openKind = null;
    }

    private void toolStarted(Map<String, Object> data) {
        if (json) {
            return;
        }
        finishLine();
        terminal.println("[工具] " + text(data, "toolName") + " "
                + CliText.oneLine(text(data, "arguments"), TOOL_ARG_LIMIT));
    }

    private void toolCompleted(Map<String, Object> data) {
        if (json) {
            return;
        }
        finishLine();
        boolean error = Boolean.TRUE.equals(data.get("isError"));
        String content = CliText.oneLine(text(data, "content"), TOOL_OUTPUT_LIMIT);
        terminal.println("[工具] " + text(data, "toolName") + (error ? " 失败" : " 完成")
                + (content.isBlank() ? "" : ": " + content));
    }

    private void compactionStarted(Map<String, Object> data) {
        if (json) {
            return;
        }
        finishLine();
        String reason = CliText.oneLine(text(data, "reason"), COMPACTION_TEXT_LIMIT);
        terminal.println("[压缩] 开始 " + text(data, "mode") + " · "
                + text(data, "tokensBefore") + " token"
                + (reason.isBlank() ? "" : " · " + reason));
    }

    private void compactionCompleted(Map<String, Object> data) {
        if (json) {
            return;
        }
        finishLine();
        String note = CliText.oneLine(text(data, "note"), COMPACTION_TEXT_LIMIT);
        terminal.println("[压缩] " + text(data, "tokensBefore") + " → "
                + text(data, "tokensAfter") + " token"
                + (Boolean.TRUE.equals(data.get("prunedOnly")) ? "（仅剪枝）" : "")
                + (note.isBlank() ? "" : " · " + note));
    }

    private void approvalResolved(Map<String, Object> data) {
        if (json) {
            return;
        }
        finishLine();
        String reason = CliText.oneLine(text(data, "reason"), COMPACTION_TEXT_LIMIT);
        terminal.println("[审批] " + text(data, "toolName") + " " + text(data, "status")
                + (reason.isBlank() ? "" : " · " + reason));
    }

    private void replayGap() {
        if (!json) {
            finishLine();
            terminal.error("[事件] 部分早期事件已被清理，本次输出可能不完整");
        }
    }

    private void failed(Map<String, Object> data) {
        finishLine();
        if (!json) {
            terminal.error("[失败] " + text(data, "errorMessage"));
        }
    }

    private static String text(Map<String, Object> data, String key) {
        Object value = data.get(key);
        return value == null ? "" : String.valueOf(value);
    }
}
