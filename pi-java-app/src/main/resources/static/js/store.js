/**
 * 前端共享状态与基础 UI 反馈。
 *
 * 这里只放跨模块复用的对象，业务动作分别放在 session-view / run 模块，
 * 避免单个脚本膨胀成难以维护的状态机。
 */

export const dom = {
    sessionList: document.getElementById("session-list"),
    newSession: document.getElementById("new-session"),
    refreshSessions: document.getElementById("refresh-sessions"),
    serverDot: document.getElementById("server-dot"),
    serverLabel: document.getElementById("server-label"),
    serverVersion: document.getElementById("server-version"),
    title: document.getElementById("session-title"),
    meta: document.getElementById("session-meta"),
    runState: document.getElementById("run-state"),
    abortButton: document.getElementById("abort-button"),
    messages: document.getElementById("messages"),
    composerForm: document.getElementById("composer-form"),
    input: document.getElementById("composer-input"),
    sendButton: document.getElementById("send-button"),
    composerModel: document.getElementById("composer-model"),
    workspaceHint: document.getElementById("workspace-hint"),
    toast: document.getElementById("toast")
};

export const state = {
    sessions: [],
    models: [],
    system: null,
    session: null,
    entries: [],
    inner: null,
    streaming: false,
    terminalSeen: false,
    controller: null,
    abortController: null,
    modelName: "",
    liveUsage: { input: 0, output: 0, totalTokens: 0 },
    stick: true,
    toastTimer: null
};

export function modelDisplayName(modelId) {
    const model = state.models.find((item) => item.id === modelId);
    return model ? model.displayName : modelId || "";
}

export function toast(message, isError = false) {
    dom.toast.textContent = message;
    dom.toast.classList.remove("hidden");
    dom.toast.classList.toggle("is-error", isError);
    clearTimeout(state.toastTimer);
    state.toastTimer = setTimeout(() => dom.toast.classList.add("hidden"), 4200);
}

export function setServer(ok, version = "") {
    dom.serverDot.className = `dot ${ok ? "dot-ok" : "dot-bad"}`;
    dom.serverLabel.textContent = ok ? "服务已连接" : "服务不可用";
    dom.serverVersion.textContent = version || "";
}

export function setRunState(text, isError = false) {
    dom.runState.textContent = text;
    dom.runState.classList.remove("hidden");
    dom.runState.classList.toggle("is-error", isError);
}
