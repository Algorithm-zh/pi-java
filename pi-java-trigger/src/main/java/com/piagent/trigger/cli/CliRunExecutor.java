package com.piagent.trigger.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.piagent.domain.agent.RunEvent;
import com.piagent.domain.agent.RunEventType;
import com.piagent.domain.shared.DomainException;
import java.io.PrintWriter;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 驱动一次 CLI run：消费事件、处理审批、等待终态并输出结果。
 *
 * <p>所有终端 I/O 都在调用线程上完成；run 线程只负责往队列里放事件。
 * 超时先请求取消，再给 run 一段收尾时间，避免直接放弃导致状态悬空。</p>
 */
@Component
public class CliRunExecutor {

    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);
    private static final Duration ABORT_GRACE = Duration.ofSeconds(5);
    private static final int APPROVAL_ARG_LIMIT = 300;

    private final ObjectMapper objectMapper;

    /**
     * 创建执行器。
     *
     * @param objectMapper JSON 输出
     */
    public CliRunExecutor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 等待 run 结束并输出结果。
     *
     * @param run 已启动的 run
     * @param terminal 终端
     * @param options 展示与交互选项
     * @return 进程退出码
     */
    public int execute(CliRun run, CliTerminal terminal, RunOptions options) {
        CliRunRenderer renderer = new CliRunRenderer(terminal, options.json());
        RunState state = new RunState();
        long deadline = System.nanoTime() + options.timeout().toNanos();
        boolean fatal = false;
        try {
            while (state.terminalData == null) {
                RunEvent event = run.poll(POLL_INTERVAL);
                if (event != null) {
                    consume(run, event, terminal, options, renderer, state);
                    continue;
                }
                if (options.onIdle() != null) {
                    options.onIdle().accept(run);
                }
                if (!state.timedOut && System.nanoTime() >= deadline) {
                    state.timedOut = true;
                    run.abort();
                    deadline = System.nanoTime() + ABORT_GRACE.toNanos();
                    terminal.error("[超时] 已请求取消，等待收尾…");
                } else if (state.timedOut && System.nanoTime() >= deadline) {
                    terminal.error("[超时] run 未在收尾时限内结束");
                    fatal = true;
                    break;
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            run.abort();
            terminal.error("[中断] 等待 run 时被中断");
            fatal = true;
        }
        renderer.finish();
        return fatal ? 1 : finish(run, terminal, options, renderer, state);
    }

    private void consume(CliRun run, RunEvent event, CliTerminal terminal, RunOptions options,
            CliRunRenderer renderer, RunState state) {
        if (event.type() == RunEventType.APPROVAL_REQUESTED) {
            state.denied |= handleApproval(run, event, terminal, options);
        } else {
            renderer.render(event);
        }
        if (event.type().isTerminal()) {
            state.terminalType = event.type();
            state.terminalData = event.data();
        }
    }

    private boolean handleApproval(CliRun run, RunEvent event, CliTerminal terminal,
            RunOptions options) {
        Map<String, Object> data = event.data();
        PrintWriter target = options.json() ? terminal.err() : terminal.out();
        target.println();
        target.println("[审批] " + text(data, "toolName") + " · 风险 " + text(data, "riskLevel"));
        target.println("  参数: " + CliText.oneLine(text(data, "arguments"), APPROVAL_ARG_LIMIT));
        String answer = null;
        boolean approved;
        if (options.autoApprove()) {
            approved = true;
            target.println("  --approve 自动批准");
        } else {
            answer = terminal.readLine(target, "批准执行? [y/N] ");
            approved = "y".equalsIgnoreCase(answer) || "yes".equalsIgnoreCase(answer);
            if (answer == null) {
                target.println("  无输入，默认拒绝");
            }
        }
        target.flush();
        String reason = approved ? null : (answer == null ? "no-input" : "cli-denied");
        String decidedBy = options.autoApprove() ? "cli-approve" : "cli";
        try {
            run.decide(text(data, "approvalId"), approved, decidedBy, reason);
        } catch (DomainException error) {
            target.println("  [审批] 提交失败: " + error.getMessage());
            target.flush();
            return true;
        }
        return !approved;
    }

    private int finish(CliRun run, CliTerminal terminal, RunOptions options,
            CliRunRenderer renderer, RunState state) {
        int exitCode = exitCode(state);
        if (options.json()) {
            printJson(run, terminal, renderer, state);
        } else {
            terminal.println(summaryLine(state));
        }
        return exitCode;
    }

    private void printJson(CliRun run, CliTerminal terminal, CliRunRenderer renderer,
            RunState state) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", run.sessionId().toString());
        result.put("runId", run.runId().toString());
        result.put("status", state.terminalData.get("status"));
        result.put("stopReason", state.terminalData.get("stopReason"));
        result.put("turns", state.terminalData.get("turns"));
        result.put("toolCalls", state.terminalData.get("toolCalls"));
        result.put("usage", state.terminalData.get("usage"));
        result.put("durationMs", state.terminalData.get("durationMs"));
        result.put("finalText", renderer.finalText());
        result.put("approvalDenied", state.denied);
        if (state.terminalData.get("errorMessage") != null) {
            result.put("errorMessage", state.terminalData.get("errorMessage"));
        }
        if (state.timedOut) {
            result.put("timedOut", true);
        }
        try {
            terminal.println(objectMapper.writeValueAsString(result));
        } catch (JsonProcessingException error) {
            terminal.error("输出 JSON 失败: " + error.getMessage());
        }
    }

    private int exitCode(RunState state) {
        String stopReason = text(state.terminalData, "stopReason");
        if (state.timedOut || "TIMEOUT".equals(stopReason)) {
            return 1;
        }
        if (state.terminalType == RunEventType.RUN_FAILED) {
            return 1;
        }
        if ("CANCELLED".equals(stopReason)) {
            return 4;
        }
        if (state.denied) {
            return 3;
        }
        return "COMPLETED".equals(text(state.terminalData, "status")) ? 0 : 1;
    }

    private String summaryLine(RunState state) {
        String status = text(state.terminalData, "status");
        String label = state.timedOut ? "超时" : switch (status) {
            case "COMPLETED" -> "完成";
            case "ABORTED" -> "已取消";
            default -> "失败";
        };
        StringBuilder line = new StringBuilder("— ").append(label)
                .append(" · ").append(text(state.terminalData, "stopReason"))
                .append(" · ").append(text(state.terminalData, "turns")).append(" 轮")
                .append(" · ").append(text(state.terminalData, "toolCalls")).append(" 工具")
                .append(" · ").append(text(state.terminalData, "durationMs")).append("ms");
        String tokens = usageTotal(state.terminalData);
        if (!tokens.isBlank()) {
            line.append(" · ").append(tokens).append(" token");
        }
        if (state.denied) {
            line.append(" · 有审批被拒绝");
        }
        return line.toString();
    }

    private static String usageTotal(Map<String, Object> data) {
        if (data.get("usage") instanceof Map<?, ?> usage && usage.get("totalTokens") != null) {
            return String.valueOf(usage.get("totalTokens"));
        }
        return "";
    }

    private static String text(Map<String, Object> data, String key) {
        if (data == null) {
            return "";
        }
        Object value = data.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static final class RunState {
        private RunEventType terminalType;
        private Map<String, Object> terminalData;
        private boolean denied;
        private boolean timedOut;
    }
}
