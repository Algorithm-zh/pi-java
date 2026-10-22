/**
 * 会话与消息的 DOM 渲染。
 *
 * 所有来自服务端/模型的文本都通过 textContent 写入，禁止 innerHTML，
 * 避免工具输出或模型输出注入脚本。
 */

export function el(tag, className, text) {
    const node = document.createElement(tag);
    if (className) {
        node.className = className;
    }
    if (text !== undefined && text !== null) {
        node.textContent = String(text);
    }
    return node;
}

const STATUS_LABELS = {
    IDLE: "空闲",
    RUNNING: "运行中",
    WAITING_APPROVAL: "等待审批",
    ARCHIVED: "已归档"
};

const STOP_LABELS = {
    STOP: "完成",
    LENGTH: "输出达到上限",
    MAX_STEPS: "达到步数上限",
    CANCELLED: "已取消",
    TIMEOUT: "超时",
    ERROR: "失败"
};

export function statusLabel(status) {
    return STATUS_LABELS[status] || status || "";
}

export function stopReasonLabel(reason) {
    return STOP_LABELS[reason] || reason || "";
}

export function basename(path) {
    if (!path) {
        return "";
    }
    const parts = String(path).split(/[\\/]/).filter(Boolean);
    return parts.length ? parts[parts.length - 1] : path;
}

export function formatNumber(value) {
    return new Intl.NumberFormat("zh-CN").format(Number(value || 0));
}

export function formatDuration(ms) {
    const value = Number(ms || 0);
    if (value < 1000) {
        return `${value} ms`;
    }
    if (value < 60_000) {
        return `${(value / 1000).toFixed(1)} s`;
    }
    return `${Math.floor(value / 60_000)} 分 ${Math.round((value % 60_000) / 1000)} 秒`;
}

export function renderSessions(container, sessions, activeId, onSelect) {
    container.replaceChildren();
    if (!sessions.length) {
        container.append(el("div", "session-empty", "还没有会话。点击上方按钮创建第一个。"));
        return;
    }
    for (const session of sessions) {
        const button = el("button", "session-item" + (session.id === activeId ? " active" : ""));
        button.type = "button";
        button.dataset.id = session.id;
        button.append(el("span", "session-dot"));
        const copy = el("span", "session-copy");
        copy.append(el("strong", "", session.title));
        copy.append(el("small", "", [statusLabel(session.status), basename(session.cwd)]
            .filter(Boolean).join(" · ")));
        button.append(copy);
        button.addEventListener("click", () => onSelect(session.id));
        container.append(button);
    }
}

/**
 * 渲染会话当前分支的 entry 列表。
 *
 * @param {HTMLElement} container 消息滚动容器
 * @param {Array<object>} entries 会话 entry
 * @returns {HTMLElement} 居中的内容容器，供流式追加复用
 */
export function renderEntries(container, entries) {
    container.replaceChildren();
    const inner = el("div", "messages-inner");
    container.append(inner);
    if (!entries.length) {
        const template = document.getElementById("empty-template");
        inner.append(template.content.cloneNode(true));
        return inner;
    }
    const results = new Map();
    for (const entry of entries) {
        if (entry.body?.role === "tool" && entry.body.toolCallId) {
            results.set(entry.body.toolCallId, entry);
        }
    }
    const consumed = new Set();
    for (const entry of entries) {
        const body = entry.body || {};
        if (entry.type === "RUN_SUMMARY") {
            inner.append(renderRunSummary(entry));
        } else if (body.role === "user") {
            const text = blocksText(body.content);
            if (text) {
                renderUserBubble(inner, text);
            }
        } else if (body.role === "assistant") {
            inner.append(renderAssistant(entry, results, consumed));
        } else if (body.role === "tool" && !consumed.has(body.toolCallId)) {
            inner.append(createToolCard({
                callId: body.toolCallId,
                toolName: body.toolName,
                args: null,
                content: blocksText(body.content),
                isError: Boolean(body.isError),
                pending: false
            }));
        }
    }
    return inner;
}

export function renderUserBubble(inner, text) {
    const row = el("div", "message user");
    row.append(el("div", "user-bubble", text));
    inner.append(row);
}

/**
 * 创建一条流式 assistant 消息。
 *
 * @param {HTMLElement} inner 消息内容容器
 * @param {string} modelName 模型名
 * @returns {{row: HTMLElement, body: HTMLElement, text: Function,
 *            thinking: Function, finish: Function}}
 */
export function beginAssistant(inner, modelName) {
    const row = el("div", "message assistant streaming");
    row.append(el("span", "avatar", "π"));
    const body = el("div", "assistant-body");
    const label = el("div", "message-label");
    label.append(document.createTextNode("PI-JAVA"), el("span", "", modelName || ""));
    body.append(label);
    row.append(body);
    inner.append(row);
    let textNode = null;
    let thinkingNode = null;
    let thinkingBody = null;
    return {
        row,
        body,
        text(value) {
            if (!textNode) {
                textNode = el("div", "text-block");
                body.append(textNode);
            }
            textNode.textContent += value;
        },
        thinking(value) {
            if (!thinkingNode) {
                thinkingNode = document.createElement("details");
                thinkingNode.className = "thinking";
                const summary = document.createElement("summary");
                summary.textContent = "思考过程";
                thinkingBody = el("div");
                thinkingNode.append(summary, thinkingBody);
                body.insertBefore(thinkingNode, textNode);
            }
            thinkingBody.textContent += value;
        },
        finish() {
            row.classList.remove("streaming");
        }
    };
}

/**
 * 创建或更新工具卡片。
 *
 * @param {HTMLElement} scope 搜索/追加范围
 * @param {{callId: string, toolName: string, args: object, content: string,
 *          isError: boolean, pending: boolean}} tool 工具状态
 * @returns {HTMLElement} 工具卡片
 */
export function upsertToolCard(scope, tool) {
    const existing = [...scope.querySelectorAll(".tool-card")]
        .find((card) => card.dataset.callId === tool.callId);
    if (existing) {
        applyToolState(existing, tool);
        return existing;
    }
    const card = createToolCard(tool);
    scope.append(card);
    return card;
}

function createToolCard(tool) {
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
        el("span", "tool-chevron", "›"));
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
    card.open = !pending;
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

function renderAssistant(entry, results, consumed) {
    const body = entry.body || {};
    const extra = body.extra || {};
    const row = el("div", "message assistant");
    row.append(el("span", "avatar", "π"));
    const content = el("div", "assistant-body");
    const label = el("div", "message-label");
    label.append(document.createTextNode("PI-JAVA"),
        el("span", "", [extra.model, timeOf(entry)].filter(Boolean).join(" · ")));
    content.append(label);
    for (const block of body.content || []) {
        if (block.type === "text" && block.text) {
            content.append(el("div", "text-block", block.text));
        } else if (block.type === "thinking" && block.thinking) {
            const details = document.createElement("details");
            details.className = "thinking";
            const summary = document.createElement("summary");
            summary.textContent = "思考过程";
            details.append(summary, el("div", "", block.thinking));
            content.append(details);
        } else if (block.type === "toolCall") {
            const result = results.get(block.toolCallId);
            if (result) {
                consumed.add(block.toolCallId);
            }
            content.append(createToolCard({
                callId: block.toolCallId,
                toolName: block.toolName,
                args: block.arguments,
                content: result ? blocksText(result.body.content) : "",
                isError: result ? Boolean(result.body.isError) : false,
                pending: !result
            }));
        }
    }
    if (extra.errorMessage) {
        content.append(el("div", "text-block is-error", extra.errorMessage));
    }
    row.append(content);
    return row;
}

function renderRunSummary(entry) {
    const extra = entry.body?.extra || {};
    const usage = extra.usage || {};
    const parts = [];
    if (extra.turns !== undefined && extra.turns !== "") {
        parts.push(`${extra.turns} 轮`);
    }
    if (extra.toolCalls !== undefined && extra.toolCalls !== "") {
        parts.push(`${extra.toolCalls} 个工具`);
    }
    if (usage.totalTokens !== undefined) {
        parts.push(`${formatNumber(usage.totalTokens)} tokens`);
    }
    if (extra.durationMs !== undefined && extra.durationMs !== "") {
        parts.push(formatDuration(extra.durationMs));
    }
    if (extra.stopReason) {
        parts.push(stopReasonLabel(extra.stopReason));
    }
    if (extra.errorMessage) {
        parts.push(extra.errorMessage);
    }
    const line = el("div", "run-summary" + (extra.stopReason === "ERROR" ? " is-error" : ""));
    line.textContent = parts.join(" · ");
    return line;
}

export function lastRunSummary(entries) {
    for (let index = entries.length - 1; index >= 0; index--) {
        if (entries[index].type === "RUN_SUMMARY") {
            return entries[index].body?.extra || null;
        }
    }
    return null;
}

export function resetForConversation(container) {
    let inner = container.querySelector(".messages-inner");
    if (!inner) {
        container.replaceChildren();
        inner = el("div", "messages-inner");
        container.append(inner);
    }
    inner.querySelectorAll(".welcome").forEach((node) => node.remove());
    return inner;
}

function blocksText(blocks) {
    return (blocks || [])
        .filter((block) => block.type === "text")
        .map((block) => block.text || "")
        .filter(Boolean)
        .join("\n");
}

function timeOf(entry) {
    if (!entry.createdAt) {
        return "";
    }
    const date = new Date(entry.createdAt);
    if (Number.isNaN(date.getTime())) {
        return "";
    }
    return date.toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit" });
}

function glyphOf(toolName) {
    const name = String(toolName || "");
    const known = { read: "RD", ls: "LS", grep: "GR", glob: "GL", bash: "SH", write: "WR" };
    if (known[name]) {
        return known[name];
    }
    return name.slice(0, 2).toUpperCase() || "T";
}

function summarizeArgs(args) {
    if (!args || !Object.keys(args).length) {
        return "";
    }
    const text = Object.entries(args)
        .map(([key, value]) => `${key}=${typeof value === "string" ? value : JSON.stringify(value)}`)
        .join(" · ");
    return text.length > 110 ? `${text.slice(0, 110)}…` : text;
}
