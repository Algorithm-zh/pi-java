/**
 * 一轮对话的执行过程与最终回复。
 *
 * 思考、工具调用和中间叙述收进默认折叠的执行面板；最终回复单独渲染为
 * Markdown。流式期间面板临时展开，run 结束后自动收起。
 */

import {
    blocksText, el, formatDuration, formatNumber, glyphOf, stopReasonLabel,
    summarizeArgs, timeOf
} from "./dom.js";
import { renderMarkdownInto } from "./markdown.js";

const APPROVAL_LABELS = {
    APPROVED: "已批准",
    DENIED: "已拒绝",
    EXPIRED: "已过期",
    CANCELLED: "已取消"
};

const THINKING_LABELS = {OFF: "关闭", LOW: "低", MEDIUM: "中", HIGH: "高"};

/**
 * 创建流式 run 的 DOM 控制器。
 *
 * @param {HTMLElement} inner 消息内容容器
 * @returns {object} 流式控制器
 */
export function createStreamingTurn(inner) {
    const wrap = el("div", "agent-turn is-live");
    inner.append(wrap);
    let process = null;
    let message = null;
    let turn = 0;
    let toolCount = 0;
    let running = true;

    function ensureProcess() {
        if (!process) {
            process = buildProcess();
            process.details.open = true;
            wrap.insertBefore(process.details, wrap.firstChild);
            updateSummary();
        }
        return process;
    }

    function beginMessage(modelName) {
        finalizeMessage();
        message = assistantRow(modelName, "");
        message.row.classList.add("answer-message");
        wrap.append(message.row);
        return {
            text(value) {
                if (!message) {
                    beginMessage(modelName);
                }
                message.raw += value;
                message.textHost.textContent = message.raw;
            },
            thinking(value) {
                const panel = ensureProcess();
                if (!panel.thinking) {
                    panel.thinking = document.createElement("details");
                    panel.thinking.className = "thinking process-thinking";
                    const summary = document.createElement("summary");
                    summary.textContent = "思考过程";
                    panel.thinkingBody = el("div", "thinking-body");
                    panel.thinking.append(summary, panel.thinkingBody);
                    panel.body.append(panel.thinking);
                }
                panel.thinkingBody.textContent += value;
            },
            finish() {
                finalizeMessage();
            }
        };
    }

    function finalizeMessage() {
        if (!message || message.finalized) {
            return;
        }
        if (message.raw.trim()) {
            renderMarkdownInto(message.textHost, message.raw);
        } else {
            message.row.remove();
        }
        message.finalized = true;
    }

    function toolStarted(tool) {
        const panel = ensureProcess();
        finalizeMessage();
        if (message && message.raw.trim()) {
            message.row.classList.remove("answer-message");
            message.row.classList.add("process-message");
            panel.body.append(message.row);
        } else if (message) {
            message.row.remove();
        }
        message = null;
        toolCount++;
        panel.body.append(toolCard({
            callId: tool.callId,
            toolName: tool.toolName,
            args: tool.arguments,
            content: "",
            isError: false,
            pending: true
        }));
        updateSummary();
    }

    function toolCompleted(tool) {
        const panel = ensureProcess();
        const card = panel.body.querySelector(`.tool-card[data-call-id="${cssEscape(tool.callId)}"]`);
        const target = card || toolCard({
            callId: tool.callId,
            toolName: tool.toolName,
            args: null,
            content: "",
            isError: Boolean(tool.isError),
            pending: false
        });
        if (!card) {
            panel.body.append(target);
            toolCount++;
        }
        applyToolState(target, {
            callId: tool.callId,
            toolName: tool.toolName,
            args: null,
            content: tool.content,
            isError: Boolean(tool.isError),
            pending: false
        });
        updateSummary();
    }

    function updateSummary(data) {
        if (!process) {
            return;
        }
        const failed = Boolean(data?.errorMessage) || data?.stopReason === "ERROR"
            || data?.status === "FAILED";
        const aborted = data?.status === "ABORTED" || data?.stopReason === "CANCELLED";
        process.details.classList.toggle("is-running", running);
        process.details.classList.toggle("is-error", !running && (failed || aborted));
        process.status.textContent = running ? "执行中"
            : failed ? "运行失败" : aborted ? "已停止" : "已完成";
        const parts = [];
        const turns = data?.turns ?? turn;
        const calls = Math.max(Number(data?.toolCalls ?? 0), toolCount);
        if (turns > 0) {
            parts.push(`${turns} 轮`);
        }
        if (calls > 0) {
            parts.push(`${calls} 个工具`);
        }
        if (!running && data?.durationMs !== undefined) {
            parts.push(formatDuration(data.durationMs));
        }
        if (!running && data?.stopReason && !["STOP", "ERROR", "CANCELLED"].includes(data.stopReason)) {
            parts.push(stopReasonLabel(data.stopReason));
        }
        if (running && !parts.length) {
            parts.push("正在生成");
        }
        process.meta.textContent = parts.join(" / ");
        if (data?.errorMessage) {
            process.details.title = data.errorMessage;
        }
    }

    return {
        beginMessage,
        setTurn(value) {
            turn = Number(value || 0);
            updateSummary();
        },
        toolStarted,
        toolCompleted,
        complete(data) {
            running = false;
            finalizeMessage();
            toolCount = Math.max(toolCount, Number(data?.toolCalls || 0));
            wrap.classList.remove("is-live");
            if (process) {
                updateSummary(data);
                process.details.open = false;
            }
        }
    };
}

/**
 * 把一轮的 entry 渲染成“执行过程 + 最终回复”。
 *
 * @param {Array<object>} items 用户消息之后的 entry
 * @param {Map<string, object>} results toolCallId → tool 结果 entry
 * @returns {HTMLElement} 轮次容器
 */
export function renderTurn(items, results) {
    const wrap = el("div", "agent-turn");
    const assistants = items.filter((entry) => entry.body?.role === "assistant");
    const finalEntry = [...assistants].reverse().find((entry) => isFinalAnswer(entry));
    const finalText = finalEntry
        ? (finalEntry.body.content || [])
            .filter((block) => block.type === "text" && block.text)
            .map((block) => block.text)
            .join("\n\n")
        : "";
    const summary = [...items].reverse().find((entry) => entry.type === "RUN_SUMMARY");
    const consumed = new Set();
    let process = null;

    function ensureProcess() {
        if (!process) {
            process = buildProcess();
            wrap.append(process.details);
        }
        return process;
    }

    for (const entry of items) {
        const body = entry.body || {};
        if (entry.type === "RUN_SUMMARY") {
            continue;
        }
        if (body.role === "assistant") {
            for (const block of body.content || []) {
                if (block.type === "thinking" && block.thinking) {
                    addThinking(ensureProcess(), block.thinking);
                } else if (block.type === "text" && block.text) {
                    if (entry !== finalEntry) {
                        const node = el("div", "process-text");
                        renderMarkdownInto(node, block.text);
                        ensureProcess().body.append(node);
                    }
                } else if (block.type === "toolCall") {
                    const result = results.get(block.toolCallId);
                    if (result) {
                        consumed.add(block.toolCallId);
                    }
                    ensureProcess().body.append(toolCard({
                        callId: block.toolCallId,
                        toolName: block.toolName,
                        args: block.arguments,
                        content: result ? blocksText(result.body.content) : "",
                        isError: result ? Boolean(result.body.isError) : false,
                        pending: !result
                    }));
                }
            }
        } else if (body.role === "tool" && !consumed.has(body.toolCallId)) {
            ensureProcess().body.append(toolCard({
                callId: body.toolCallId,
                toolName: body.toolName,
                args: null,
                content: blocksText(body.content),
                isError: Boolean(body.isError),
                pending: false
            }));
        } else if (["APPROVAL", "COMPACTION", "BRANCH_SUMMARY", "MODEL_CHANGE"].includes(entry.type)) {
            ensureProcess().body.append(renderNote(entry));
        }
    }

    if (process) {
        const extra = summary?.body?.extra || {};
        const failed = extra.stopReason === "ERROR" || Boolean(extra.errorMessage);
        const aborted = extra.stopReason === "CANCELLED";
        process.details.classList.add("is-done");
        process.details.classList.toggle("is-error", failed || aborted);
        process.status.textContent = failed ? "运行失败" : aborted ? "已停止" : "已完成";
        const parts = [];
        const renderedTools = process.body.querySelectorAll(".tool-card").length;
        const toolCalls = Math.max(Number(extra.toolCalls || 0), renderedTools);
        if (extra.turns !== undefined) {
            parts.push(`${extra.turns} 轮`);
        }
        if (toolCalls > 0) {
            parts.push(`${toolCalls} 个工具`);
        }
        if (extra.durationMs !== undefined) {
            parts.push(formatDuration(extra.durationMs));
        }
        if (extra.stopReason && !["STOP", "ERROR", "CANCELLED"].includes(extra.stopReason)) {
            parts.push(stopReasonLabel(extra.stopReason));
        }
        process.meta.textContent = parts.join(" / ");
        if (extra.errorMessage) {
            process.details.title = extra.errorMessage;
        }
    }

    if (finalText.trim()) {
        const answer = assistantRow(finalEntry.body.extra?.model || "", timeOf(finalEntry));
        answer.row.classList.add("answer-message");
        renderMarkdownInto(answer.textHost, finalText);
        wrap.append(answer.row);
    }
    return wrap;
}

function isFinalAnswer(entry) {
    const blocks = entry.body?.content || [];
    const hasText = blocks.some((block) => block.type === "text" && block.text?.trim());
    const hasTool = blocks.some((block) => block.type === "toolCall");
    return hasText && !hasTool;
}

function assistantRow(modelName, time) {
    const row = el("div", "message assistant");
    row.append(el("span", "avatar", "π"));
    const body = el("div", "assistant-body");
    const label = el("div", "message-label");
    label.append(document.createTextNode("pi-java"),
        el("span", "", [modelName, time].filter(Boolean).join(" / ")));
    const textHost = el("div", "text-block");
    body.append(label, textHost);
    row.append(body);
    return {row, body, label, textHost, raw: "", finalized: false};
}

function buildProcess() {
    const details = document.createElement("details");
    details.className = "agent-process";
    const summary = document.createElement("summary");
    const state = el("span", "process-state");
    state.append(el("span", "process-led"), el("span", "process-state-text", "执行中"));
    const meta = el("span", "process-meta");
    summary.append(state, meta, el("span", "process-chevron", "›"));
    const body = el("div", "process-body");
    details.append(summary, body);
    return {
        details,
        body,
        status: state.querySelector(".process-state-text"),
        meta,
        thinking: null,
        thinkingBody: null
    };
}

function addThinking(panel, text) {
    if (!panel.thinking) {
        panel.thinking = document.createElement("details");
        panel.thinking.className = "thinking process-thinking";
        const summary = document.createElement("summary");
        summary.textContent = "思考过程";
        panel.thinkingBody = el("div", "thinking-body");
        panel.thinking.append(summary, panel.thinkingBody);
        panel.body.append(panel.thinking);
    }
    panel.thinkingBody.textContent += text;
}

function toolCard(tool) {
    const card = document.createElement("details");
    card.className = "tool-card";
    if (tool.callId) {
        card.dataset.callId = tool.callId;
    }
    const head = document.createElement("summary");
    head.className = "tool-head";
    head.append(
        el("span", "tool-glyph", glyphOf(tool.toolName)),
        el("span", "tool-name", tool.toolName || "tool"),
        el("span", "tool-args", summarizeArgs(tool.args)),
        el("span", "tool-status"),
        el("span", "tool-chevron", "›")
    );
    const body = el("div", "tool-body");
    card.append(head, body);
    applyToolState(card, tool);
    return card;
}

function applyToolState(card, tool) {
    const pending = Boolean(tool.pending);
    const isError = Boolean(tool.isError);
    card.classList.toggle("is-pending", pending);
    card.classList.toggle("is-done", !pending && !isError);
    card.classList.toggle("is-error", !pending && isError);
    card.open = pending;
    card.querySelector(".tool-status").textContent = pending ? "运行中"
        : isError ? "失败" : "完成";
    if (tool.args) {
        card.querySelector(".tool-args").textContent = summarizeArgs(tool.args);
    }
    const body = card.querySelector(".tool-body");
    body.replaceChildren();
    if (pending) {
        body.append(el("div", "tool-waiting", "正在等待工具结果…"));
        return;
    }
    const output = document.createElement("pre");
    output.className = "tool-output";
    output.textContent = tool.content || "（无输出）";
    body.append(output);
}

/**
 * 渲染审批、压缩、分支摘要与模型切换等历史条目。
 *
 * @param {object} entry 会话 entry
 * @returns {HTMLElement} 备注节点
 */
export function renderNote(entry) {
    if (entry.type === "APPROVAL") {
        return approvalNote(entry);
    }
    if (entry.type === "MODEL_CHANGE") {
        return modelNote(entry);
    }
    if (entry.type === "COMPACTION") {
        return collapsibleNote(entry, "compaction-note",
            `上下文已压缩，压缩前 ${formatNumber(entry.body?.extra?.tokensBefore)} tokens`);
    }
    if (entry.type === "BRANCH_SUMMARY") {
        const from = entry.body?.extra?.fromEntryId ? String(entry.body.extra.fromEntryId).slice(0, 8) : "—";
        return collapsibleNote(entry, "branch-note", `分支摘要，起点 ${from}`);
    }
    return el("div", "history-note", "未知记录");
}

function approvalNote(entry) {
    const extra = entry.body?.extra || {};
    const decision = APPROVAL_LABELS[extra.decision] || extra.decision || "已处理";
    const note = el("div", "history-note approval-note"
        + (extra.decision === "DENIED" ? " is-denied" : ""));
    note.append(el("span", "history-note-title",
        `审批：${entry.body?.toolName || "工具"}`), el("span", "history-note-state", decision));
    if (extra.decidedBy) {
        note.append(el("span", "history-note-meta", `由 ${extra.decidedBy} 决定`));
    }
    if (extra.reason) {
        note.append(el("span", "history-note-meta", `意见：${extra.reason}`));
    }
    return note;
}

function modelNote(entry) {
    const extra = entry.body?.extra || {};
    const note = el("div", "history-note model-note");
    note.append(el("span", "history-note-title", "模型切换"),
        el("span", "", extra.modelProfileId || ""),
        el("span", "history-note-meta",
            `思考 ${THINKING_LABELS[extra.thinkingLevel] || extra.thinkingLevel || "关闭"}`));
    return note;
}

function collapsibleNote(entry, className, label) {
    const details = document.createElement("details");
    details.className = `history-note ${className}`;
    const summary = document.createElement("summary");
    summary.textContent = label;
    details.append(summary, el("pre", "history-note-body",
        entry.body?.extra?.summary || "（无摘要）"));
    return details;
}

function cssEscape(value) {
    return String(value || "").replace(/["\\]/g, "\\$&");
}
