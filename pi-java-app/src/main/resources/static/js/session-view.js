/**
 * 会话视图：加载、切换、刷新会话，并维护头部与输入区状态。
 */

import * as api from "./api.js";
import * as approval from "./approval.js";
import * as contextMeter from "./context-meter.js";
import { el, renderEntries, renderSessions, statusLabel } from "./render.js";
import { dom, modelDisplayName, setRunState, state, toast } from "./store.js";
import * as usage from "./usage.js";

let reconnectHandler = null;

/**
 * 注入“会话仍有活跃 run”时的重连处理器，避免视图层直接依赖运行模块。
 *
 * @param {(sessionId: string) => void} handler 重连函数
 */
export function bindReconnect(handler) {
    reconnectHandler = handler;
}

export async function refreshSessions(redraw = true) {
    state.sessions = await api.listSessions();
    if (redraw) {
        renderSessionList();
    }
}

/**
 * 按搜索框过滤后重绘侧栏会话列表。
 */
export function renderSessionList() {
    const query = (dom.sessionSearch?.value || "").trim().toLowerCase();
    const sessions = query
        ? state.sessions.filter((session) => [
            session.title, session.cwd, statusLabel(session.status)
        ].join(" ").toLowerCase().includes(query))
        : state.sessions;
    renderSessions(dom.sessionList, sessions, state.session?.id, openSession,
        query ? "没有找到匹配的会话，换个关键词试试。" : undefined);
}

export function setComposerEnabled(enabled) {
    dom.input.disabled = !enabled;
    dom.sendButton.disabled = !enabled || state.streaming;
    if (enabled) {
        dom.input.placeholder = "问点什么，或者粘贴一段代码…";
    } else {
        dom.input.placeholder = state.session?.status === "ARCHIVED"
            ? "会话已归档，只能查看"
            : "先新建或选择一个会话";
    }
}

export async function openSession(sessionId, updateUrl = true) {
    if (state.streaming) {
        toast("当前会话仍在生成，请先停止", true);
        return;
    }
    try {
        const [session, entries] = await Promise.all([
            api.getSession(sessionId),
            api.listEntries(sessionId)
        ]);
        state.session = session;
        state.entries = entries;
        state.controller = null;
        state.turn = null;
        state.terminalSeen = false;
        state.modelName = session.modelProfileId;
        state.inner = renderEntries(dom.messages, entries);
        approval.reset();
        renderSessionList();
        updateHeader();
        usage.restore(entries);
        setComposerEnabled(session.status !== "ARCHIVED");
        contextMeter.refresh();
        state.stick = true;
        scrollToBottom(true);
        if (updateUrl) {
            history.replaceState(null, "", `?session=${encodeURIComponent(session.id)}`);
        }
        if (session.status === "RUNNING" || session.status === "WAITING_APPROVAL") {
            approval.showPending(session.id);
            reconnectHandler?.(session.id);
        }
    } catch (error) {
        toast(error.message || "加载会话失败", true);
    }
}

export async function reloadSession() {
    if (!state.session) {
        return;
    }
    const sessionId = state.session.id;
    try {
        const [session, entries, sessions] = await Promise.all([
            api.getSession(sessionId),
            api.listEntries(sessionId),
            api.listSessions()
        ]);
        state.session = session;
        state.entries = entries;
        state.sessions = sessions;
        state.turn = null;
        state.inner = renderEntries(dom.messages, entries);
        renderSessionList();
        updateHeader();
        usage.restore(entries);
        setComposerEnabled(session.status !== "ARCHIVED");
        contextMeter.refresh();
        state.stick = true;
        scrollToBottom(true);
    } catch (error) {
        toast(error.message || "同步会话失败", true);
    }
}

export function updateHeader() {
    if (!state.session) {
        dom.title.textContent = "开始一段新对话";
        dom.meta.replaceChildren();
        dom.composerModel.textContent = "";
        dom.abortButton.classList.add("hidden");
        dom.runState.classList.add("hidden");
        dom.contextWidget.classList.add("hidden");
        dom.sessionSettingsOpen.classList.add("hidden");
        return;
    }
    const session = state.session;
    dom.sessionSettingsOpen.classList.remove("hidden");
    dom.title.textContent = session.title;
    dom.meta.replaceChildren();
    const status = el("span", "meta-pill");
    status.append(el("span", `meta-dot is-${statusKey(session.status)}`),
        document.createTextNode(statusLabel(session.status)));
    dom.meta.append(status);
    const model = modelDisplayName(session.modelProfileId);
    if (model) {
        dom.meta.append(el("span", "meta-pill meta-model", model));
    }
    if (session.origin === "SUBAGENT") {
        dom.meta.append(el("span", "meta-pill", "子会话"));
    } else if (session.cwd) {
        const path = el("span", "meta-pill meta-path");
        const code = document.createElement("code");
        code.textContent = session.cwd;
        path.append(code);
        dom.meta.append(path);
    }
    dom.composerModel.textContent = model;
    if (state.streaming) {
        return;
    }
    dom.abortButton.classList.add("hidden");
    if (session.status === "RUNNING") {
        setRunState("运行中");
    } else if (session.status === "WAITING_APPROVAL") {
        setRunState("等待审批");
    } else {
        dom.runState.classList.add("hidden");
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

export function scrollToBottom(force = false) {
    if (force || state.stick) {
        dom.messages.scrollTop = dom.messages.scrollHeight;
    }
}
