/**
 * 会话列表与对话流的 DOM 渲染。
 *
 * 对话按用户消息分组：执行过程交给 turn-view 渲染成可折叠面板，
 * 最终回复单独显示。所有文本都通过 textContent/DOM 节点写入。
 */

import {
    basename, blocksText, el, formatDuration, formatNumber, glyphOf, statusLabel, stopReasonLabel
} from "./dom.js";
import { describeCommand } from "./tools.js";
import {renderNote, renderTurn} from "./turn-view.js";

export {
    basename, el, formatDuration, formatNumber, glyphOf, statusLabel, stopReasonLabel
};

const COLLAPSE_KEY = "pi-java-collapsed-groups";
const NO_WORKSPACE = "__no-workspace__";

const collapsedGroups = loadCollapsedGroups();
let openMenu = null;

document.addEventListener("click", (event) => {
    if (openMenu && !openMenu.contains(event.target)) {
        closeMenu();
    }
});
document.addEventListener("keydown", (event) => {
    if (event.key === "Escape") {
        closeMenu();
    }
});

function loadCollapsedGroups() {
    try {
        const stored = JSON.parse(localStorage.getItem(COLLAPSE_KEY) || "[]");
        return new Set(Array.isArray(stored) ? stored : []);
    } catch {
        return new Set();
    }
}

function saveCollapsedGroups() {
    try {
        localStorage.setItem(COLLAPSE_KEY, JSON.stringify([...collapsedGroups]));
    } catch {
        // 隐私模式写不了 localStorage，折叠状态只在本次渲染内生效。
    }
}

function closeMenu() {
    if (openMenu) {
        openMenu.classList.add("hidden");
        openMenu = null;
    }
}

/**
 * 渲染按工作区分组的会话列表。
 *
 * @param {HTMLElement} container 侧栏列表容器
 * @param {Array<object>} sessions 会话
 * @param {string} activeId 当前会话 ID
 * @param {object} options 回调与状态
 * @param {string} emptyText 空列表文案
 */
export function renderSessions(container, sessions, activeId, options = {}, emptyText) {
    container.replaceChildren();
    closeMenu();
    if (!sessions.length) {
        container.append(el("div", "session-empty",
            emptyText || "还没有会话。点上面的「新建会话」开始吧。"));
        return;
    }
    for (const group of groupByWorkspace(sessions)) {
        container.append(renderGroup(group, activeId, options));
    }
}

function groupByWorkspace(sessions) {
    const groups = new Map();
    for (const session of sessions) {
        const key = session.cwd || NO_WORKSPACE;
        if (!groups.has(key)) {
            groups.set(key, {
                key,
                label: session.cwd ? basename(session.cwd) || session.cwd : "未指定工作区",
                path: session.cwd || "",
                items: []
            });
        }
        groups.get(key).items.push(session);
    }
    return [...groups.values()];
}

function renderGroup(group, activeId, options) {
    const section = el("section", "session-group");
    const collapsed = collapsedGroups.has(group.key) && !options.searching;
    section.classList.toggle("is-collapsed", collapsed);
    const head = el("button", "session-group-head");
    head.type = "button";
    head.setAttribute("aria-expanded", String(!collapsed));
    if (group.path && group.path !== group.label) {
        head.title = group.path;
    }
    head.append(el("span", "session-group-chevron"),
        el("span", "session-group-name", group.label),
        el("span", "session-group-count", String(group.items.length)));
    head.addEventListener("click", () => {
        const isCollapsed = section.classList.toggle("is-collapsed");
        head.setAttribute("aria-expanded", String(!isCollapsed));
        if (isCollapsed) {
            collapsedGroups.add(group.key);
        } else {
            collapsedGroups.delete(group.key);
        }
        saveCollapsedGroups();
    });
    section.append(head);
    for (const session of group.items) {
        section.append(renderSessionRow(session, activeId, options));
    }
    return section;
}

function renderSessionRow(session, activeId, options) {
    const row = el("div", "session-row");
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
    button.addEventListener("click", () => options.onSelect?.(session.id));

    const more = el("button", "session-more", "⋯");
    more.type = "button";
    more.title = "会话操作";
    more.setAttribute("aria-haspopup", "menu");
    more.setAttribute("aria-label", `会话操作：${session.title}`);

    const menu = el("div", "session-menu hidden");
    menu.setAttribute("role", "menu");
    const rename = el("button", "session-menu-item", "重命名");
    rename.type = "button";
    rename.setAttribute("role", "menuitem");
    rename.addEventListener("click", () => {
        closeMenu();
        options.onRename?.(session);
    });
    const remove = el("button", "session-menu-item is-danger", "删除");
    remove.type = "button";
    remove.setAttribute("role", "menuitem");
    remove.addEventListener("click", () => {
        closeMenu();
        options.onDelete?.(session);
    });
    menu.append(rename, remove);
    more.addEventListener("click", (event) => {
        event.stopPropagation();
        if (openMenu === menu) {
            closeMenu();
            return;
        }
        closeMenu();
        menu.classList.remove("hidden");
        openMenu = menu;
    });
    menu.addEventListener("click", (event) => event.stopPropagation());

    row.append(button, more, menu);
    return row;
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
            const content = group.user.body?.content || [];
            const text = blocksText(content);
            const images = content
                .filter((block) => block.type === "image" && block.url)
                .map((block) => ({url: block.url, name: block.name || "图片"}));
            if (text || images.length) {
                renderUserBubble(inner, text, images);
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

/**
 * 渲染用户消息气泡。
 *
 * @param {HTMLElement} inner 消息容器
 * @param {string} text 用户文本
 * @param {Array<{url: string, name: string}>} images 图片，可为空
 */
export function renderUserBubble(inner, text, images = []) {
    const row = el("div", "message user");
    const command = describeCommand(text);
    if (!command) {
        const bubble = el("div", "user-bubble" + (images.length ? " has-images" : ""));
        if (images.length) {
            bubble.append(renderUserImages(images));
        }
        if (text) {
            bubble.append(el("div", "user-text", text));
        }
        row.append(bubble);
        inner.append(row);
        return;
    }
    const bubble = el("div", "user-bubble is-tool-call");
    if (images.length) {
        bubble.append(renderUserImages(images));
    }
    const head = el("div", "user-tool-head");
    head.append(el("span", "user-tool-label", `调用工具 · ${command.displayName}`));
    if (command.summary) {
        head.append(el("span", "user-tool-summary", command.summary));
    }
    bubble.append(head);
    const details = document.createElement("details");
    details.className = "user-tool-raw";
    const summary = document.createElement("summary");
    summary.textContent = "查看原始命令";
    details.append(summary, el("code", "user-tool-raw-text", command.raw));
    bubble.append(details);
    row.append(bubble);
    inner.append(row);
}

function renderUserImages(images) {
    const strip = el("div", "user-images" + (images.length === 1 ? " is-single" : ""));
    for (const image of images) {
        const link = el("a", "user-image");
        link.href = image.url;
        link.target = "_blank";
        link.rel = "noopener";
        link.title = image.name || "查看原图";
        const thumb = document.createElement("img");
        thumb.src = image.url;
        thumb.alt = image.name || "用户上传的图片";
        thumb.loading = "lazy";
        link.append(thumb);
        strip.append(link);
    }
    return strip;
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
