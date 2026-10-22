/**
 * Web 入口：加载环境信息、装配模块、绑定全局交互。
 */

import * as api from "./api.js";
import * as approval from "./approval.js";
import * as dialog from "./dialog.js";
import { start as startRun, reconnectRun, abort as abortRun } from "./run.js";
import {
    bindReconnect, openSession, refreshSessions, scrollToBottom, setComposerEnabled
} from "./session-view.js";
import { dom, setServer, state, toast } from "./store.js";

async function init() {
    bindReconnect(reconnectRun);
    dialog.init(createSession);
    approval.init();
    wireEvents();
    try {
        const [system, models] = await Promise.all([api.systemInfo(), api.listModels()]);
        state.system = system;
        state.models = models;
        setServer(true, system.version);
        dom.workspaceHint.textContent = system.workspaceRoot;
        await refreshSessions(false);
        const requested = new URLSearchParams(location.search).get("session");
        const target = state.sessions.find((item) => item.id === requested) || state.sessions[0];
        if (target) {
            await openSession(target.id, false);
        } else {
            setComposerEnabled(false);
        }
    } catch (error) {
        setServer(false);
        toast(error.message || "无法连接服务端", true);
    }
}

async function createSession(payload) {
    const session = await api.createSession(payload);
    await refreshSessions();
    await openSession(session.id);
}

function submitComposer() {
    const text = dom.input.value.trim();
    if (!text || state.streaming) {
        return;
    }
    if (!state.session) {
        dialog.open(state.models, state.system);
        toast("请先新建一个会话", true);
        return;
    }
    dom.input.value = "";
    resizeInput();
    startRun(text);
}

function resizeInput() {
    dom.input.style.height = "auto";
    dom.input.style.height = `${Math.min(dom.input.scrollHeight, 210)}px`;
}

function wireEvents() {
    dom.newSession.addEventListener("click", () => dialog.open(state.models, state.system));
    dom.refreshSessions.addEventListener("click", () => refreshSessions().catch((error) => {
        toast(error.message, true);
    }));
    dom.composerForm.addEventListener("submit", (event) => {
        event.preventDefault();
        submitComposer();
    });
    dom.input.addEventListener("input", resizeInput);
    dom.input.addEventListener("keydown", (event) => {
        if (event.key === "Enter" && !event.shiftKey && !event.isComposing) {
            event.preventDefault();
            dom.composerForm.requestSubmit();
        }
    });
    dom.abortButton.addEventListener("click", abortRun);
    dom.messages.addEventListener("scroll", () => {
        const remaining = dom.messages.scrollHeight - dom.messages.scrollTop
            - dom.messages.clientHeight;
        state.stick = remaining < 90;
    });
    dom.messages.addEventListener("click", (event) => {
        const chip = event.target.closest("[data-prompt]");
        if (!chip) {
            return;
        }
        if (!state.session) {
            dialog.open(state.models, state.system);
            return;
        }
        dom.input.value = chip.dataset.prompt || "";
        resizeInput();
        submitComposer();
    });
    window.addEventListener("beforeunload", () => {
        state.abortController?.abort();
    });
}

init();
