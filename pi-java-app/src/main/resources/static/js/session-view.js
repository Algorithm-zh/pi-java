/**
 * 会话视图：加载、切换、刷新会话，并维护头部与输入区状态。
 */

import * as api from "./api.js";
import * as approval from "./approval.js";
import * as contextMeter from "./context-meter.js";
import { basename, renderEntries, renderSessions, statusLabel } from "./render.js";
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
        renderSessions(dom.sessionList, state.sessions, state.session?.id, openSession);
    }
}

export function setComposerEnabled(enabled) {
    dom.input.disabled = !enabled;
    dom.sendButton.disabled = !enabled || state.streaming;
    if (enabled) {
        dom.input.placeholder = "输入消息，Enter 发送，Shift + Enter 换行";
    } else {
        dom.input.placeholder = state.session?.status === "ARCHIVED"
            ? "会话已归档，只读查看"
            : "请先新建或选择会话";
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
        state.terminalSeen = false;
        state.modelName = session.modelProfileId;
        state.inner = renderEntries(dom.messages, entries);
        approval.reset();
        renderSessions(dom.sessionList, state.sessions, session.id, openSession);
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
        state.inner = renderEntries(dom.messages, entries);
        renderSessions(dom.sessionList, sessions, session.id, openSession);
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
        dom.title.textContent = "选择或新建一个会话";
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
    const parts = [
        statusLabel(session.status),
        modelDisplayName(session.modelProfileId),
        session.origin === "SUBAGENT" ? "子会话" : basename(session.cwd)
    ].filter(Boolean);
    dom.meta.append(document.createTextNode(parts.join(" / ")));
    const cwd = document.createElement("code");
    cwd.textContent = session.cwd;
    dom.meta.append(document.createTextNode("  "), cwd);
    dom.composerModel.textContent = modelDisplayName(session.modelProfileId);
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

export function scrollToBottom(force = false) {
    if (force || state.stick) {
        dom.messages.scrollTop = dom.messages.scrollHeight;
    }
}
