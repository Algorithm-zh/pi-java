/**
 * 会话列表与对话流的 DOM 渲染。
 *
 * 对话按用户消息分组：执行过程交给 turn-view 渲染成可折叠面板，
 * 最终回复单独显示。所有文本都通过 textContent/DOM 节点写入。
 */

import {
    basename, blocksText, el, formatDuration, formatNumber, glyphOf, statusLabel, stopReasonLabel
} from "./dom.js";
import {renderNote, renderTurn} from "./turn-view.js";

export {
    basename, el, formatDuration, formatNumber, glyphOf, statusLabel, stopReasonLabel
};

export function renderSessions(container, sessions, activeId, onSelect, emptyText) {
    container.replaceChildren();
    if (!sessions.length) {
        container.append(el("div", "session-empty",
            emptyText || "还没有会话。点上面的「新建会话」开始吧。"));
        return;
    }
    for (const session of sessions) {
        const archived = session.status === "ARCHIVED";
        const button = el("button", "session-item"
            + (session.id === activeId ? " active" : "")
            + (archived ? " is-archived" : ""));
        button.type = "button";
        button.dataset.id = session.id;
        button.append(el("span", "session-badge",
            session.origin === "SUBAGENT" ? "↳" : "π"));
        const copy = el("span", "session-copy");
        copy.append(el("strong", "", session.title));
        const meta = el("span", "session-meta-line");
        appendStatus(meta, session);
        if (session.origin !== "SUBAGENT") {
            meta.append(el("span", "session-cwd", basename(session.cwd)));
        }
        copy.append(meta);
        button.append(copy);
        button.addEventListener("click", () => onSelect(session.id));
        container.append(button);
    }
}

function appendStatus(meta, session) {
    if (session.origin === "SUBAGENT") {
        meta.append(el("span", `session-dot is-${statusKey(session.status)}`),
            el("span", "", "子会话"));
        return;
    }
    if (session.status === "RUNNING" || session.status === "WAITING_APPROVAL"
        || session.status === "ARCHIVED") {
        meta.append(el("span", `session-dot is-${statusKey(session.status)}`),
            el("span", "", statusLabel(session.status)));
    }
}

function statusKey(status) {
    if (status === "RUNNING") {
        return "running";
    }
    if (status === "WAITING_APPROVAL") {
        return "waiting";
    }
    if (status === "ARCHIVED") {
        return "archived";
    }
    return "idle";
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
    for (const group of groupTurns(entries)) {
        if (group.user) {
            const text = blocksText(group.user.body?.content);
            if (text) {
                renderUserBubble(inner, text);
            }
            if (group.items.length) {
                inner.append(renderTurn(group.items, results));
            }
            continue;
        }
        const chat = group.items.some((entry) => ["assistant", "tool"]
            .includes(entry.body?.role));
        if (chat) {
            inner.append(renderTurn(group.items, results));
        } else {
            group.items.forEach((entry) => inner.append(renderNote(entry)));
        }
    }
    return inner;
}

function groupTurns(entries) {
    const groups = [];
    let current = null;
    for (const entry of entries) {
        if (entry.body?.role === "user") {
            current = {user: entry, items: []};
            groups.push(current);
        } else if (current) {
            current.items.push(entry);
        } else {
            groups.push({user: null, items: [entry]});
        }
    }
    return groups;
}

export function renderUserBubble(inner, text) {
    const row = el("div", "message user");
    row.append(el("div", "user-bubble", text));
    inner.append(row);
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
