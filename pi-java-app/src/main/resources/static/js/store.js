/**
 * 前端共享状态与基础 UI 反馈。
 *
 * 这里只放跨模块复用的对象，业务动作分别放在 session-view / run 模块，
 * 避免单个脚本膨胀成难以维护的状态机。
 */

export const dom = {
    sidebar: document.getElementById("sidebar"),
    sidebarBackdrop: document.getElementById("sidebar-backdrop"),
    sidebarToggle: document.getElementById("sidebar-toggle"),
    sidebarClose: document.getElementById("sidebar-close"),
    sessionSearch: document.getElementById("session-search"),
    themeToggle: document.getElementById("theme-toggle"),
    scrollBottom: document.getElementById("scroll-bottom"),
    sessionList: document.getElementById("session-list"),
    newSession: document.getElementById("new-session"),
    refreshSessions: document.getElementById("refresh-sessions"),
    openSettings: document.getElementById("open-settings"),
    serverDot: document.getElementById("server-dot"),
    serverLabel: document.getElementById("server-label"),
    serverVersion: document.getElementById("server-version"),
    title: document.getElementById("session-title"),
    meta: document.getElementById("session-meta"),
    runState: document.getElementById("run-state"),
    contextWidget: document.getElementById("context-widget"),
    contextFill: document.getElementById("context-fill"),
    contextLabel: document.getElementById("context-label"),
    compactButton: document.getElementById("compact-button"),
    sessionSettingsOpen: document.getElementById("session-settings-open"),
    abortButton: document.getElementById("abort-button"),
    messages: document.getElementById("messages"),
    composerForm: document.getElementById("composer-form"),
    slashMenu: document.getElementById("slash-menu"),
    input: document.getElementById("composer-input"),
    sendButton: document.getElementById("send-button"),
    attachButton: document.getElementById("attach-button"),
    composerFile: document.getElementById("composer-file"),
    composerAttachments: document.getElementById("composer-attachments"),
    composerModel: document.getElementById("composer-model"),
    composerQueue: document.getElementById("composer-queue"),
    planDock: document.getElementById("plan-dock"),
    planToggle: document.getElementById("plan-toggle"),
    planSeal: document.getElementById("plan-seal"),
    planSealText: document.getElementById("plan-seal-text"),
    planTitle: document.getElementById("plan-title"),
    planCurrent: document.getElementById("plan-current"),
    planVerifyChip: document.getElementById("plan-verify-chip"),
    planCount: document.getElementById("plan-count"),
    planRailFill: document.getElementById("plan-rail-fill"),
    planDetail: document.getElementById("plan-detail"),
    changesDock: document.getElementById("changes-dock"),
    changesToggle: document.getElementById("changes-toggle"),
    changesText: document.getElementById("changes-text"),
    changesStats: document.getElementById("changes-stats"),
    changesUndo: document.getElementById("changes-undo"),
    changesDetail: document.getElementById("changes-detail"),
    workspaceHint: document.getElementById("workspace-hint"),
    approvalDialog: document.getElementById("approval-dialog"),
    approvalRisk: document.getElementById("approval-risk"),
    approvalGlyph: document.getElementById("approval-glyph"),
    approvalToolName: document.getElementById("approval-tool-name"),
    approvalToolCall: document.getElementById("approval-tool-call"),
    approvalArguments: document.getElementById("approval-arguments"),
    approvalDigest: document.getElementById("approval-digest"),
    approvalCountdown: document.getElementById("approval-countdown"),
    approvalReason: document.getElementById("approval-reason"),
    approvalQueue: document.getElementById("approval-queue"),
    approvalApprove: document.getElementById("approval-approve"),
    approvalDeny: document.getElementById("approval-deny"),
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
    turn: null,
    abortController: null,
    queued: new Map(),
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
