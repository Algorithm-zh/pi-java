/**
 * Web 入口：加载环境信息、装配模块、绑定全局交互。
 */

import * as api from "./api.js";
import * as approval from "./approval.js";
import * as changes from "./changes.js";
import * as contextMeter from "./context-meter.js";
import * as dialog from "./dialog.js";
import * as plan from "./plan.js";
import {
    abort as abortRun, detach, enqueue, reconnectRun, start as startRun, syncQueue
} from "./run.js";
import {
    bindDetach, bindReconnect, bindSessionReady, initSessionActions, openSession,
    refreshSessions, reloadSession, renderSessionList, scrollToBottom, setComposerEnabled
} from "./session-view.js";
import * as sessionSettings from "./session-settings.js";
import * as settings from "./settings.js";
import * as slash from "./slash.js";
import * as toolForm from "./tool-form.js";
import * as tools from "./tools.js";
import { dom, setServer, state, toast } from "./store.js";

const THEME_KEY = "pi-java-theme";
let pendingPrompt = "";

async function init() {
    initTheme();
    bindReconnect(reconnectRun);
    bindDetach(detach);
    bindSessionReady(syncQueue);
    changes.initChanges();
    changes.bindReload(reloadSession);
    plan.initPlan();
    initSessionActions();
    dialog.init(createSession, () => settings.open("models"), () => {
        pendingPrompt = "";
    });
    approval.init();
    settings.init();
    sessionSettings.init();
    contextMeter.init({ onReload: reloadSession });
    toolForm.init({onRun: sendText});
    slash.init({onResize: resizeInput, onToolChosen: toolForm.open});
    wireEvents();
    try {
        const [system, models] = await Promise.all([api.systemInfo(), api.listModels()]);
        state.system = system;
        state.models = models;
        setServer(true, system.version);
        dom.workspaceHint.textContent = system.workspaceRoot;
        await tools.load();
        await refreshSessions(false);
        const requested = new URLSearchParams(location.search).get("session");
        const target = state.sessions.find((item) => item.id === requested) || state.sessions[0];
        if (target) {
            await openSession(target.id, false);
        } else {
            // 还没会话时也允许输入：提交后会引导创建，草稿不会丢。
            setComposerEnabled(true);
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
    closeSidebar();
    if (pendingPrompt) {
        const text = pendingPrompt;
        pendingPrompt = "";
        dom.input.value = "";
        resizeInput();
        startRun(text);
    }
}

function submitComposer() {
    const text = dom.input.value.trim();
    if (!text) {
        return;
    }
    if (!state.session) {
        pendingPrompt = text;
        dialog.open(state.models, state.system);
        return;
    }
    slash.hide();
    dom.input.value = "";
    resizeInput();
    sendText(text);
}

function sendText(text) {
    if (!text) {
        return;
    }
    if (!state.session) {
        pendingPrompt = text;
        dialog.open(state.models, state.system);
        return;
    }
    const busy = state.streaming
        || state.session.status === "RUNNING"
        || state.session.status === "WAITING_APPROVAL";
    if (busy) {
        // 运行中的工具调用无法再插话，先排队，本轮结束后自动发出。
        enqueue(text);
        toast("已排队，本轮结束后自动发送");
        return;
    }
    startRun(text);
}

function resizeInput() {
    dom.input.style.height = "auto";
    dom.input.style.height = `${Math.min(dom.input.scrollHeight, 210)}px`;
}

function wireEvents() {
    dom.newSession.addEventListener("click", () => dialog.open(state.models, state.system));
    dom.openSettings.addEventListener("click", () => {
        closeSidebar();
        settings.open("models");
    });
    dom.refreshSessions.addEventListener("click", () => refreshSessions().catch((error) => {
        toast(error.message, true);
    }));
    dom.sidebarToggle.addEventListener("click", openSidebar);
    dom.sidebarClose.addEventListener("click", closeSidebar);
    dom.sidebarBackdrop.addEventListener("click", closeSidebar);
    dom.sessionSearch.addEventListener("input", renderSessionList);
    dom.sessionSearch.addEventListener("keydown", (event) => {
        if (event.key === "Escape") {
            dom.sessionSearch.value = "";
            renderSessionList();
            dom.sessionSearch.blur();
        }
    });
    dom.sessionList.addEventListener("click", (event) => {
        if (event.target.closest(".session-item")) {
            closeSidebar();
        }
    });
    dom.scrollBottom.addEventListener("click", () => {
        state.stick = true;
        scrollToBottom(true);
        dom.scrollBottom.classList.add("hidden");
    });
    dom.themeToggle.addEventListener("click", () => {
        applyTheme(document.documentElement.dataset.theme === "dark" ? "light" : "dark");
    });
    dom.composerForm.addEventListener("submit", (event) => {
        event.preventDefault();
        submitComposer();
    });
    dom.input.addEventListener("input", resizeInput);
    dom.input.addEventListener("keydown", (event) => {
        if (slash.handleKeydown(event)) {
            return;
        }
        if (event.key === "Enter" && !event.shiftKey && !event.isComposing) {
            event.preventDefault();
            dom.composerForm.requestSubmit();
        }
    });
    dom.abortButton.addEventListener("click", abortRun);
    dom.messages.addEventListener("scroll", updateScrollButton);
    dom.messages.addEventListener("click", (event) => {
        const chip = event.target.closest("[data-prompt]");
        if (!chip) {
            return;
        }
        if (!state.session) {
            pendingPrompt = chip.dataset.prompt || "";
            dialog.open(state.models, state.system);
            return;
        }
        dom.input.value = chip.dataset.prompt || "";
        resizeInput();
        submitComposer();
    });
    document.addEventListener("keydown", (event) => {
        if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "k") {
            event.preventDefault();
            if (isMobile()) {
                openSidebar();
            }
            dom.sessionSearch.focus();
            dom.sessionSearch.select();
        }
        if (event.key === "Escape" && dom.sidebar.classList.contains("open")) {
            closeSidebar();
        }
    });
    window.addEventListener("resize", () => {
        if (!isMobile()) {
            closeSidebar();
        }
        updateScrollButton();
    });
    window.addEventListener("beforeunload", () => {
        state.abortController?.abort();
    });
}

function updateScrollButton() {
    const remaining = dom.messages.scrollHeight - dom.messages.scrollTop
        - dom.messages.clientHeight;
    state.stick = remaining < 90;
    dom.scrollBottom.classList.toggle("hidden", state.stick);
}

function isMobile() {
    return window.matchMedia("(max-width: 900px)").matches;
}

function openSidebar() {
    dom.sidebar.classList.add("open");
    dom.sidebarBackdrop.classList.remove("hidden");
    dom.sidebarToggle.setAttribute("aria-expanded", "true");
}

function closeSidebar() {
    dom.sidebar.classList.remove("open");
    dom.sidebarBackdrop.classList.add("hidden");
    dom.sidebarToggle.setAttribute("aria-expanded", "false");
}

function initTheme() {
    applyTheme(document.documentElement.dataset.theme === "dark" ? "dark" : "light", false);
}

function applyTheme(theme, persist = true) {
    document.documentElement.dataset.theme = theme;
    if (persist) {
        try {
            localStorage.setItem(THEME_KEY, theme);
        } catch (error) {
            // 隐私模式下写不了 localStorage，主题仍应在本次会话内生效。
        }
    }
    document.getElementById("meta-theme-color")?.setAttribute(
        "content", theme === "dark" ? "#121613" : "#f3efe3");
    const label = theme === "dark" ? "切换到浅色主题" : "切换到深色主题";
    dom.themeToggle.title = label;
    dom.themeToggle.setAttribute("aria-label", label);
}

init();
