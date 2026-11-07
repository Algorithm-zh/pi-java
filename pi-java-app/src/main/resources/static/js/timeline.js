/**
 * 决策时间线：查看每次运行前的检查点与证据，并回退到任意检查点。
 *
 * 节点顺序、current 标记与 rewindable 全部由服务端决定，前端不自行推断分支；
 * 所有服务端文本都用 textContent 写入。
 */

import * as api from "./api.js";
import { el, stopReasonLabel } from "./dom.js";
import { renderEntries } from "./render.js";
import { dom, state, toast } from "./store.js";

let phase = "closed";
let timeline = null;
let loadedSessionId = null;
let selectedCheckpoint = null;
let selectedBranch = null;
let previewMode = "rewind";
let previewResult = null;
let reloadHandler = null;
let requestId = 0;
let previewId = 0;
let historyRequestId = 0;
let compareRequestId = 0;
let compareBranchId = null;
let historyView = null;
const historyStack = [];

const REWIND_TIMEOUT_MS = 15000;

/**
 * 注入回退后的会话重载回调，避免与 session-view 形成循环依赖。
 *
 * @param {() => Promise<void>} handler 重载函数
 */
export function bindReload(handler) {
    reloadHandler = handler;
}

/**
 * 页面初始化：绑定面板、预览与键盘交互。
 */
export function initTimeline() {
    if (dom.timelinePanel) {
        dom.timelinePanel.inert = true;
    }
    dom.timelineToggle?.addEventListener("click", togglePanel);
    dom.timelineClose?.addEventListener("click", closePanel);
    dom.timelineSpine?.addEventListener("click", onSpineClick);
    dom.timelineList?.addEventListener("click", onListClick);
    dom.timelinePreviewClose?.addEventListener("click", () => closePreview());
    dom.timelineCancel?.addEventListener("click", () => closePreview());
    dom.timelinePreviewForm?.addEventListener("submit", (event) => {
        event.preventDefault();
        confirmPreview();
    });
    dom.timelinePreview?.addEventListener("cancel", (event) => {
        // 回退请求在飞行中时不允许关闭，超时兜底保证 Escape 最终可用。
        if (phase === "rewinding") {
            event.preventDefault();
        }
    });
    dom.timelinePreview?.addEventListener("close", () => {
        if (phase === "previewing") {
            closePreview(true);
        }
    });
    dom.messages?.addEventListener("click", onHistoryClick);
    dom.rewindHistoryBody?.addEventListener("click", onHistoryClick);
    dom.rewindHistoryBack?.addEventListener("click", goBackHistory);
    dom.rewindHistoryClose?.addEventListener("click", closeHistory);
    dom.rewindHistoryDialog?.addEventListener("close", () => {
        historyRequestId++;
        clearHistory();
    });
    dom.compareClose?.addEventListener("click", () => closeCompare());
    dom.compareDialog?.addEventListener("close", () => {
        compareRequestId++;
        clearCompare();
    });
    dom.compareResume?.addEventListener("click", () => {
        const branchEntryId = compareBranchId;
        closeCompare();
        if (branchEntryId) {
            previewBranch(branchEntryId);
        }
    });
    document.addEventListener("keydown", (event) => {
        if (event.key === "Escape" && !dom.timelinePreview?.open) {
            closePanel();
        }
    });
}

/**
 * 拉取当前会话的时间线；切换会话或 run 结束后调用。
 */
export async function refresh() {
    const sessionId = state.session?.id;
    if (!sessionId) {
        reset();
        return;
    }
    if (loadedSessionId && loadedSessionId !== sessionId) {
        closeHistory();
    }
    if (phase === "rewinding") {
        return;
    }
    // 预览打开时不让后台刷新（workspace.changed / finishRun）覆盖预览上下文。
    if (phase === "previewing" && dom.timelinePreview?.open) {
        return;
    }
    const token = ++requestId;
    phase = "loading";
    showToggle();
    renderStatus("正在读取决策时间线…");
    try {
        const value = await api.sessionTimeline(sessionId);
        if (token !== requestId || state.session?.id !== sessionId) {
            return;
        }
        timeline = value;
        loadedSessionId = sessionId;
        phase = "ready";
        render();
    } catch (error) {
        if (token !== requestId || state.session?.id !== sessionId) {
            return;
        }
        timeline = null;
        loadedSessionId = sessionId;
        phase = "error";
        dom.timelineList.replaceChildren();
        dom.timelineSpine?.replaceChildren();
        dom.timelineSpine?.classList.add("hidden");
        renderStatus(error.message || "读取时间线失败", true);
        showToggle();
    }
}

/**
 * 清空面板；切换或删除会话时调用。
 */
export function reset() {
    requestId++;
    previewId++;
    closeHistory();
    closeCompare();
    phase = "closed";
    timeline = null;
    loadedSessionId = null;
    dom.timelineToggle?.classList.add("hidden");
    closePanel();
    closePreview(true);
    dom.timelineList?.replaceChildren();
    dom.timelineSpine?.replaceChildren();
    dom.timelineSpine?.classList.add("hidden");
    if (dom.timelineSubtitle) {
        dom.timelineSubtitle.textContent = "";
    }
    renderStatus("");
}

/**
 * 简报里的“查看被放弃的记录”：只读加载旧叶子的分支，不改变会话。
 * 只截取回退点之后被放弃的片段；更早的回退可以在片段里继续点开。
 */
function onHistoryClick(event) {
    if (!(event.target instanceof Element)) {
        return;
    }
    const button = event.target.closest("[data-rewind-from]");
    if (!button || !button.dataset.rewindFrom) {
        return;
    }
    const nested = Boolean(dom.rewindHistoryBody?.contains(button));
    if (nested && historyView) {
        historyStack.push(historyView);
    } else {
        historyStack.length = 0;
    }
    loadHistory({
        from: button.dataset.rewindFrom,
        after: button.dataset.rewindAfter || "",
        kind: button.dataset.rewindKind || "rewind",
        count: Number(button.dataset.rewindCount || 0)
    });
}

function goBackHistory() {
    const previous = historyStack.pop();
    if (previous) {
        loadHistory(previous);
    }
}

async function loadHistory(view) {
    const sessionId = state.session?.id;
    if (!sessionId || !view?.from || !dom.rewindHistoryDialog || !dom.rewindHistoryBody) {
        return;
    }
    const token = ++historyRequestId;
    historyView = view;
    updateHistoryChrome();
    setHistoryError("");
    dom.rewindHistoryBody.replaceChildren(el("p", "timeline-preview-loading",
        view.kind === "resume" ? "正在读取离开的记录…" : "正在读取被放弃的记录…"));
    if (!dom.rewindHistoryDialog.open) {
        dom.rewindHistoryDialog.showModal();
    }
    try {
        const branch = await api.listEntries(sessionId, view.from);
        if (token !== historyRequestId || state.session?.id !== sessionId) {
            return;
        }
        const segment = sliceAfter(branch, view.after);
        if (!segment.length) {
            dom.rewindHistoryBody.replaceChildren(el("p", "timeline-preview-loading",
                view.kind === "resume"
                    ? "这次切换没有离开任何对话记录。"
                    : "这次回退没有放弃任何对话记录。"));
            return;
        }
        renderEntries(dom.rewindHistoryBody, segment);
    } catch (error) {
        if (token !== historyRequestId) {
            return;
        }
        dom.rewindHistoryBody.replaceChildren();
        setHistoryError(error?.status === 404
            ? "这段记录已被旧版本的回退删除，无法再找回。"
            : (error.message || "读取被放弃的记录失败"));
    }
}

/**
 * 回退点之前的 entry 仍在当前对话里，弹窗只展示之后被放弃的片段。
 *
 * @param {Array<object>} entries 旧叶子所在分支
 * @param {string} afterEntryId 回退后重新接上的 entry；空表示从根开始
 * @returns {Array<object>} 被放弃的 entry
 */
function sliceAfter(entries, afterEntryId) {
    if (!afterEntryId) {
        return entries;
    }
    const index = entries.findIndex((entry) => entry.id === afterEntryId);
    return index < 0 ? entries : entries.slice(index + 1);
}

function updateHistoryChrome() {
    if (dom.rewindHistoryBack) {
        dom.rewindHistoryBack.classList.toggle("hidden", historyStack.length === 0);
    }
    if (dom.rewindHistoryLead) {
        const count = Number(historyView?.count || 0);
        const isResume = historyView?.kind === "resume";
        if (!historyView) {
            dom.rewindHistoryLead.textContent = "只读查看，不会加入当前上下文。";
        } else if (isResume) {
            dom.rewindHistoryLead.textContent = count > 0
                ? `这次切换离开了 ${count} 条记录。只读查看，不会加入当前上下文。`
                : "以下是切换时离开的对话。只读查看，不会加入当前上下文。";
        } else {
            dom.rewindHistoryLead.textContent = count > 0
                ? `这次回退放弃了 ${count} 条记录。只读查看，不会加入当前上下文。`
                : "以下是回退时放弃的对话。只读查看，不会加入当前上下文。";
        }
    }
}

function closeHistory() {
    historyRequestId++;
    if (dom.rewindHistoryDialog?.open) {
        dom.rewindHistoryDialog.close();
    }
    clearHistory();
}

function clearHistory() {
    historyView = null;
    historyStack.length = 0;
    dom.rewindHistoryBody?.replaceChildren();
    setHistoryError("");
    updateHistoryChrome();
}

/**
 * 打开某一次分支切换的对比。只读，不会改变当前分支。
 *
 * @param {string} branchEntryId 分支简报 entry
 */
async function openCompare(branchEntryId) {
    const sessionId = state.session?.id;
    if (!sessionId || !branchEntryId || !dom.compareDialog) {
        return;
    }
    const token = ++compareRequestId;
    compareBranchId = branchEntryId;
    dom.compareSummary.textContent = "正在读取两条分支…";
    dom.compareAbandoned?.replaceChildren();
    dom.compareCurrent?.replaceChildren();
    dom.compareFiles?.replaceChildren();
    dom.compareResume?.classList.add("hidden");
    setCompareError("");
    if (!dom.compareDialog.open) {
        dom.compareDialog.showModal();
    }
    try {
        const value = await api.sessionComparison(sessionId, branchEntryId);
        if (token !== compareRequestId || state.session?.id !== sessionId) {
            return;
        }
        renderCompare(value);
    } catch (error) {
        if (token !== compareRequestId) {
            return;
        }
        dom.compareSummary.textContent = "";
        dom.compareAbandoned?.replaceChildren();
        dom.compareCurrent?.replaceChildren();
        dom.compareFiles?.replaceChildren();
        setCompareError(error?.status === 409
            ? "这条分支简报已经不在当前分支上，无法对比。"
            : (error.message || "读取分叉对比失败"));
    }
}

function closeCompare() {
    compareRequestId++;
    if (dom.compareDialog?.open) {
        dom.compareDialog.close();
    }
    clearCompare();
}

function clearCompare() {
    if (dom.compareSummary) {
        dom.compareSummary.textContent = "";
    }
    dom.compareAbandoned?.replaceChildren();
    dom.compareCurrent?.replaceChildren();
    dom.compareFiles?.replaceChildren();
    dom.compareResume?.classList.add("hidden");
    compareBranchId = null;
    setCompareError("");
}

function setCompareError(message) {
    if (!dom.compareError) {
        return;
    }
    dom.compareError.textContent = message || "";
    dom.compareError.classList.toggle("hidden", !message);
}

function renderCompare(value) {
    compareBranchId = value?.branchEntryId || null;
    dom.compareResume?.classList.toggle("hidden", !compareBranchId);
    if (!value?.available) {
        dom.compareSummary.textContent =
            "这个会话还没有分支切换记录，暂时没有可对比的分支。";
        renderCompareSide(dom.compareAbandoned, null);
        renderCompareSide(dom.compareCurrent, null);
        dom.compareFiles.replaceChildren(
            el("p", "compare-empty", "切换一次分支之后，这里会显示两条分支的差别。"));
        return;
    }
    dom.compareSummary.textContent = value.summary || "";
    renderCompareSide(dom.compareAbandoned, value.abandoned);
    renderCompareSide(dom.compareCurrent, value.current);
    renderCompareFiles(value.files || {});
}

function renderCompareSide(container, side) {
    if (!container) {
        return;
    }
    container.replaceChildren();
    const intents = Array.isArray(side?.intents) ? side.intents : [];
    const files = Array.isArray(side?.files) ? side.files : [];
    const denied = Array.isArray(side?.approvals?.deniedTools)
        ? side.approvals.deniedTools : [];
    const failed = Array.isArray(side?.verifications?.failedCommands)
        ? side.verifications.failedCommands : [];
    const unfinished = Array.isArray(side?.plan?.unfinished)
        ? side.plan.unfinished : [];
    if (!intents.length && !Number(side?.runs) && !files.length
            && !denied.length && !failed.length && !unfinished.length) {
        container.append(el("p", "compare-empty", "没有后续动作"));
        return;
    }
    if (intents.length) {
        container.append(compareSection("意图"));
        const list = el("ul", "compare-list");
        for (const intent of intents) {
            list.append(el("li", "", intent));
        }
        container.append(list);
    }
    const facts = el("div", "compare-facts");
    facts.append(el("span", "compare-fact", `${Number(side?.turns) || 0} 轮对话`),
        el("span", "compare-fact", `${Number(side?.runs) || 0} 个 run`));
    if (Number(side?.approvals?.denied) > 0) {
        facts.append(el("span", "compare-fact is-warn",
            `${side.approvals.denied} 次拒绝`));
    }
    if (Number(side?.verifications?.failed) > 0) {
        facts.append(el("span", "compare-fact is-warn",
            `${side.verifications.failed} 次验证失败`));
    }
    container.append(facts);
    const decisions = el("ul", "compare-list compare-decisions");
    for (const tool of denied) {
        decisions.append(el("li", "is-warn", `拒绝 ${tool}`));
    }
    for (const command of failed) {
        decisions.append(el("li", "is-warn", `验证失败 · ${command}`));
    }
    for (const item of unfinished) {
        decisions.append(el("li", "", `未完成 · ${item}`));
    }
    if (decisions.childElementCount) {
        container.append(compareSection("关键决定"), decisions);
    }
    if (files.length) {
        container.append(compareSection("文件"));
        const list = el("ul", "compare-list compare-file-list");
        for (const file of files) {
            list.append(el("li", "", `${file.path}  +${file.additions} −${file.deletions}`));
        }
        container.append(list);
    }
}

function renderCompareFiles(files) {
    if (!dom.compareFiles) {
        return;
    }
    dom.compareFiles.replaceChildren();
    const groups = [
        ["仅另一条分支", files.onlyAbandoned],
        ["仅当前分支", files.onlyCurrent],
        ["两边都改过", files.both]
    ];
    let shown = 0;
    for (const [label, paths] of groups) {
        if (!Array.isArray(paths) || !paths.length) {
            continue;
        }
        shown++;
        const group = el("div", "compare-file-group");
        group.append(compareSection(`${label} · ${paths.length}`));
        const list = el("ul", "compare-list compare-file-list");
        for (const path of paths) {
            list.append(el("li", "", path));
        }
        group.append(list);
        dom.compareFiles.append(group);
    }
    if (!shown) {
        dom.compareFiles.append(el("p", "compare-empty", "两条分支没有文件差异。"));
    } else if (files.truncated) {
        dom.compareFiles.append(el("p", "compare-empty", "文件过多，列表已截断。"));
    }
}

function compareSection(title) {
    return el("h4", "compare-section-title", title);
}

function setHistoryError(message) {
    if (!dom.rewindHistoryError) {
        return;
    }
    dom.rewindHistoryError.textContent = message || "";
    dom.rewindHistoryError.classList.toggle("hidden", !message);
}

function togglePanel() {
    if (dom.timelinePanel?.classList.contains("open")) {
        closePanel();
        return;
    }
    dom.timelinePanel?.classList.add("open");
    dom.timelinePanel?.setAttribute("aria-hidden", "false");
    if (dom.timelinePanel) {
        dom.timelinePanel.inert = false;
    }
    dom.timelineToggle?.setAttribute("aria-expanded", "true");
    dom.timelineClose?.focus();
    if (phase === "error" || loadedSessionId !== state.session?.id) {
        refresh();
    } else if (timeline) {
        render();
    }
}

function closePanel() {
    const wasOpen = Boolean(dom.timelinePanel?.classList.contains("open"));
    dom.timelinePanel?.classList.remove("open");
    dom.timelinePanel?.setAttribute("aria-hidden", "true");
    if (dom.timelinePanel) {
        dom.timelinePanel.inert = true;
    }
    dom.timelineToggle?.setAttribute("aria-expanded", "false");
    if (wasOpen && dom.timelineToggle && !dom.timelineToggle.classList.contains("hidden")) {
        dom.timelineToggle.focus();
    }
}

function showToggle() {
    dom.timelineToggle?.classList.remove("hidden");
}

function render() {
    const list = dom.timelineList;
    if (!list) {
        return;
    }
    const nodes = Array.isArray(timeline?.nodes) ? timeline.nodes : [];
    if (dom.timelineSubtitle) {
        dom.timelineSubtitle.textContent = nodes.length
            ? `${nodes.length} 个决策点${timeline.available ? "" : " · 无 Git 检查点"}`
            : "还没有可回看的运行";
    }
    list.replaceChildren();
    renderSpine(nodes);
    if (!nodes.length) {
        list.append(el("p", "timeline-empty",
            "还没有决策点，发送第一条消息后出现。"));
        renderStatus("");
        return;
    }
    for (const node of nodes) {
        list.append(renderNode(node));
    }
    const canRewind = nodes.some((node) => node.rewindable);
    renderStatus(timelineHint(canRewind));
}

function timelineHint(canRewind) {
    const status = state.session?.status;
    if (status === "ARCHIVED") {
        return "会话已归档，决策时间线只读。";
    }
    if (state.streaming || status === "RUNNING" || status === "WAITING_APPROVAL") {
        return "等待当前运行结束，之后可以回退。";
    }
    if (!timeline.available) {
        return "当前工作区没有 Git 检查点，只能查看运行证据。";
    }
    return canRewind ? "选一个检查点，先预览再回退。"
        : "当前状态下不能回退，只能查看证据。";
}

function line(className, symbol, text) {
    const row = el("li", className);
    const marker = el("span", "timeline-mark", symbol);
    marker.setAttribute("aria-hidden", "true");
    row.append(marker, document.createTextNode(text));
    return row;
}

function evidenceSection(title) {
    const section = document.createElement("details");
    section.className = "timeline-evidence-section";
    section.open = window.matchMedia("(min-width: 721px)").matches;
    section.append(el("summary", "", title));
    return section;
}

function renderNode(node) {
    const card = el("article", `timeline-node is-${String(node.type).toLowerCase()}`
        + (node.current ? " is-current" : ""));
    if (node.checkpointEntryId) {
        card.dataset.node = node.checkpointEntryId;
    }
    const head = el("div", "timeline-node-head");
    const copy = el("div", "timeline-copy");
    copy.append(el("span", "timeline-title", nodeTitle(node)),
        el("span", "timeline-meta", formatTime(node.at)));
    head.append(copy);
    if (node.current) {
        head.append(el("span", "timeline-badge", "现在"));
    }
    card.append(head);
    if (node.type === "RUN") {
        card.append(statsRow(node));
        if (node.stopReason) {
            const detail = Number(node.turns) > 0
                ? ` · ${node.turns} 轮 · ${Number(node.toolCalls) || 0} 次工具调用`
                : "";
            card.append(el("span", "timeline-stop",
                `结束：${stopReasonLabel(node.stopReason)}${detail}`));
        }
        const evidence = evidenceDetails(node);
        if (evidence) {
            card.append(evidence);
        }
    } else {
        const isResume = node.type === "RESUME";
        card.append(el("p", "timeline-brief", node.brief || "（无简报）"),
            el("span", "timeline-meta",
                `离开 ${node.leftEntries || 0} 条记录 · `
                + `${node.leftRuns || 0} 次运行`));
        if (node.branchEntryId) {
            const actions = el("div", "timeline-branch-actions");
            const compare = el("button", "timeline-compare", "对比两条分支");
            compare.type = "button";
            compare.dataset.compare = node.branchEntryId;
            const resume = el("button", "timeline-resume",
                isResume ? "切到另一条分支" : "切回这条分支");
            resume.type = "button";
            resume.dataset.resume = node.branchEntryId;
            actions.append(compare, resume);
            card.append(actions);
        }
    }
    if (node.rewindable && node.checkpointEntryId) {
        const button = el("button", "timeline-rewind", "回到这次运行之前");
        button.type = "button";
        button.dataset.checkpoint = node.checkpointEntryId;
        card.append(button);
    }
    return card;
}

function renderSpine(nodes) {
    if (!dom.timelineSpine) {
        return;
    }
    dom.timelineSpine.replaceChildren();
    dom.timelineSpine.classList.toggle("hidden", !nodes.length);
    for (const node of nodes) {
        if (node.type === "REWIND" || node.type === "RESUME") {
            const mark = el("span", "timeline-tick is-rewind"
                + (node.type === "RESUME" ? " is-resume" : ""));
            mark.title = nodeTitle(node);
            dom.timelineSpine.append(mark);
            continue;
        }
        if (!node.checkpointEntryId) {
            continue;
        }
        const tick = el("button", "timeline-tick" + (node.current ? " is-current" : ""));
        tick.type = "button";
        tick.dataset.spine = node.checkpointEntryId;
        tick.title = `${formatTime(node.at)} · ${shorten(nodeTitle(node), 28)}`;
        tick.setAttribute("aria-label", tick.title);
        dom.timelineSpine.append(tick);
    }
}

function onSpineClick(event) {
    if (!(event.target instanceof Element)) {
        return;
    }
    const tick = event.target.closest("button[data-spine]");
    if (!tick) {
        return;
    }
    if (!dom.timelinePanel?.classList.contains("open")) {
        togglePanel();
    }
    const cards = Array.from(dom.timelineList?.querySelectorAll("[data-node]") || []);
    if (!cards.length) {
        // 刷新尚未落地：只打开面板，不报错。
        return;
    }
    const target = cards.find((card) => card.dataset.node === tick.dataset.spine);
    target?.scrollIntoView({ block: "center", behavior: "smooth" });
}

function nodeTitle(node) {
    if (node.type === "REWIND") {
        return `在 ${formatTime(node.at)} 回溯`;
    }
    if (node.type === "RESUME") {
        return `在 ${formatTime(node.at)} 切回`;
    }
    return node.intent || "（没有文字意图）";
}

function statsRow(node) {
    const row = el("div", "timeline-stats");
    const plan = node.plan || {};
    if (Number(plan.total) > 0) {
        row.append(el("span", "timeline-stat",
            `计划 ${Number(plan.done || 0)}/${Number(plan.total)}`));
    }
    const approvals = node.approvals || {};
    if (Number(approvals.total) > 0) {
        row.append(el("span", "timeline-stat",
            `审批 ${Number(approvals.total)} · 拒绝 ${Number(approvals.denied || 0)}`));
    }
    const verification = node.verification || {};
    if (Number(verification.total) > 0) {
        row.append(el("span", "timeline-stat",
            `验证 ${Number(verification.failed || 0)} 失败`));
    }
    const changes = node.changes || {};
    if (Number(changes.turns) > 0) {
        row.append(el("span", "timeline-stat",
            `改动 ${Number(changes.turns)} 轮 · ${Number(changes.files || 0)} 文件`));
    }
    return row;
}

function evidenceDetails(node) {
    const evidence = node.evidence || {};
    const sections = [];
    const planItems = Array.isArray(evidence.plan) ? evidence.plan : [];
    if (planItems.length) {
        const section = evidenceSection("计划");
        const list = el("ul", "timeline-evidence-list");
        for (const item of planItems) {
            const done = item.status === "done";
            list.append(line(done ? "is-done" : "", done ? "✓" : "○",
                `${planStatus(item.status)} · ${item.content}`));
        }
        section.append(list);
        sections.push(section);
    }
    const approvals = Array.isArray(evidence.approvals) ? evidence.approvals : [];
    if (approvals.length) {
        const section = evidenceSection("审批");
        const list = el("ul", "timeline-evidence-list");
        for (const approval of approvals) {
            const denied = approval.decision === "DENIED";
            list.append(line(denied ? "is-denied" : "is-done", denied ? "✕" : "✓",
                `${approval.toolName} · ${denied ? "已拒绝" : "已批准"}`
                + (approval.reason ? ` · ${approval.reason}` : "")));
        }
        section.append(list);
        sections.push(section);
    }
    const verifications = Array.isArray(evidence.verifications) ? evidence.verifications : [];
    if (verifications.length) {
        const section = evidenceSection("验证");
        const list = el("ul", "timeline-evidence-list");
        for (const verification of verifications) {
            const exit = verification.exitCode === null || verification.exitCode === undefined
                ? "" : ` · exit ${verification.exitCode}`;
            const output = String(verification.output || "")
                .replace(/\s+/g, " ").trim();
            list.append(line(verification.passed ? "is-done" : "is-denied",
                verification.passed ? "✓" : "✕",
                `${verification.command} · ${verification.passed ? "通过" : "失败"}${exit}`
                + (output ? ` · ${shorten(output, 100)}` : "")));
        }
        section.append(list);
        sections.push(section);
    }
    const changes = Array.isArray(evidence.changes) ? evidence.changes : [];
    if (changes.length) {
        const section = evidenceSection("改动");
        const list = el("ul", "timeline-evidence-list");
        for (const change of changes) {
            const files = Array.isArray(change.files) ? change.files : [];
            const item = el("li", "");
            item.append(el("span", "", `第 ${change.turn} 轮 · ${files.length} 个文件`
                + (change.suggestedCommit ? ` · ${change.suggestedCommit}` : "")));
            const paths = el("ul", "timeline-evidence-list");
            for (const file of files) {
                const size = file.binary
                    ? " · 二进制"
                    : ` · +${file.additions} −${file.deletions}`;
                paths.append(el("li", "", `${file.path} · ${changeKindLabel(file.kind)}${size}`));
            }
            item.append(paths);
            if (change.diff) {
                const diff = document.createElement("pre");
                diff.className = "timeline-evidence-diff";
                diff.textContent = change.diff;
                item.append(diff);
            }
            list.append(item);
        }
        section.append(list);
        sections.push(section);
    }
    if (!sections.length) {
        return null;
    }
    const details = document.createElement("details");
    details.className = "timeline-evidence";
    details.append(el("summary", "", "查看证据"));
    for (const section of sections) {
        details.append(section);
    }
    return details;
}

function planStatus(status) {
    switch (status) {
        case "done":
            return "完成";
        case "in_progress":
            return "进行中";
        default:
            return "待办";
    }
}

function changeKindLabel(kind) {
    switch (kind) {
        case "ADDED":
            return "新增";
        case "DELETED":
            return "删除";
        default:
            return "修改";
    }
}

function onListClick(event) {
    if (!(event.target instanceof Element)) {
        return;
    }
    const compare = event.target.closest("button[data-compare]");
    if (compare) {
        openCompare(compare.dataset.compare);
        return;
    }
    const resume = event.target.closest("button[data-resume]");
    if (resume) {
        previewBranch(resume.dataset.resume);
        return;
    }
    const button = event.target.closest("button[data-checkpoint]");
    if (button) {
        preview(button.dataset.checkpoint);
    }
}

function nodeOf(checkpointEntryId) {
    return (timeline?.nodes || []).find((node) =>
        node.checkpointEntryId === checkpointEntryId) || null;
}

function nodeOfBranch(branchEntryId) {
    return (timeline?.nodes || []).find((node) =>
        node.branchEntryId === branchEntryId) || null;
}

async function previewBranch(branchEntryId) {
    const sessionId = state.session?.id;
    if (!sessionId || !branchEntryId
            || (phase !== "ready" && phase !== "previewing")) {
        return;
    }
    const token = ++previewId;
    previewMode = "resume";
    selectedBranch = branchEntryId;
    selectedCheckpoint = null;
    previewResult = null;
    phase = "previewing";
    openPreview();
    dom.timelinePreviewTitle.textContent = "切到另一条分支";
    dom.timelinePreviewKicker.textContent = "切换预览";
    dom.timelinePreviewBody.replaceChildren(el("p", "timeline-preview-loading",
        "正在计算要改动的文件…"));
    setPreviewError("");
    dom.timelineNote.value = "";
    dom.timelineConfirm.disabled = true;
    try {
        const result = await api.resumeBranch(sessionId, {
            branchEntryId,
            dryRun: true
        });
        if (token !== previewId || state.session?.id !== sessionId) {
            return;
        }
        previewResult = result;
        renderPreview(result);
    } catch (error) {
        if (token !== previewId) {
            return;
        }
        phase = "ready";
        closePreview(true);
        toast(error.message || "无法预览切换", true);
    }
}

async function preview(checkpointEntryId) {
    const sessionId = state.session?.id;
    if (!sessionId || !checkpointEntryId
            || (phase !== "ready" && phase !== "previewing")) {
        return;
    }
    const token = ++previewId;
    previewMode = "rewind";
    selectedBranch = null;
    selectedCheckpoint = checkpointEntryId;
    previewResult = null;
    phase = "previewing";
    openPreview();
    dom.timelinePreviewTitle.textContent = "回到这次运行之前";
    dom.timelinePreviewKicker.textContent = "回退预览";
    dom.timelinePreviewBody.replaceChildren(el("p", "timeline-preview-loading",
        "正在计算要还原的文件…"));
    setPreviewError("");
    dom.timelineNote.value = "";
    dom.timelineConfirm.disabled = true;
    try {
        const result = await api.rewindSession(sessionId, {
            checkpointEntryId,
            dryRun: true
        });
        if (token !== previewId || state.session?.id !== sessionId) {
            return;
        }
        previewResult = result;
        phase = "previewing";
        renderPreview(result);
    } catch (error) {
        if (token !== previewId) {
            return;
        }
        phase = "ready";
        closePreview(true);
        toast(error.message || "无法预览回退", true);
    }
}

function openPreview() {
    if (!dom.timelinePreview.open) {
        dom.timelinePreview.showModal();
    }
}

function renderPreview(result) {
    const body = dom.timelinePreviewBody;
    if (!body) {
        return;
    }
    body.replaceChildren();
    if (previewMode === "resume") {
        renderResumePreview(result, body);
    } else {
        renderRewindPreview(result, body);
    }
    const available = result.available !== false;
    dom.timelineConfirm.disabled = !available || !result.restorable;
    dom.timelineConfirm.textContent = !available
        ? (previewMode === "resume" ? "无法切换" : "无法回退")
        : result.restorable
            ? (previewMode === "resume" ? "确认切换" : "确认回退")
            : (previewMode === "resume" ? "无法切换" : "无法回退");
    setPreviewError("");
}

function renderResumePreview(result, body) {
    const node = nodeOfBranch(selectedBranch);
    if (dom.timelinePreviewTitle) {
        dom.timelinePreviewTitle.textContent = node?.at
            ? `切到 ${formatTime(node.at)} 的另一条分支`
            : "切到另一条分支";
    }
    if (dom.timelinePreviewKicker) {
        dom.timelinePreviewKicker.textContent = !result.available
            ? "无法切换"
            : result.restorable ? "切换预览" : "存在冲突";
    }
    appendPreviewBrief(body, result);
    body.append(previewFacts([
        `离开 ${result.leftEntries || 0} 条记录`,
        `离开 ${result.leftRuns || 0} 次运行`,
        `改动 ${(result.files || []).length} 个文件`,
        `目标 ${result.targetEntries || 0} 条记录`,
        `目标 ${result.targetRuns || 0} 次运行`
    ]));
    if (!result.available && result.unavailableReason) {
        body.append(el("p", "timeline-preview-unavailable", result.unavailableReason));
    }
    appendPreviewFiles(body, result, "这些文件在目标之后又被修改，切换被整体放弃：");
}

function renderRewindPreview(result, body) {
    const node = nodeOf(selectedCheckpoint);
    if (dom.timelinePreviewTitle) {
        dom.timelinePreviewTitle.textContent = node?.intent
            ? `回到「${shorten(node.intent, 48)}」之前`
            : "回到这次运行之前";
    }
    if (dom.timelinePreviewKicker) {
        dom.timelinePreviewKicker.textContent = result.restorable ? "回退预览" : "存在冲突";
    }
    appendPreviewBrief(body, result);
    body.append(previewFacts([
        `丢弃 ${result.discardedEntries || 0} 条记录`,
        `丢弃 ${result.discardedRuns || 0} 次运行`,
        `还原 ${(result.files || []).length} 个文件`
    ]));
    appendPreviewFiles(body, result, "这些文件在目标之后又被修改，回退被整体放弃：");
}

function previewFacts(items) {
    const facts = el("div", "timeline-preview-facts");
    for (const item of items) {
        facts.append(el("span", "", item));
    }
    return facts;
}

function appendPreviewBrief(body, result) {
    if (result.brief) {
        body.append(el("p", "timeline-preview-brief", result.brief));
    }
}

function appendPreviewFiles(body, result, conflictTitle) {
    const files = Array.isArray(result.files) ? result.files : [];
    if (files.length) {
        const list = el("ul", "timeline-preview-files");
        for (const path of files) {
            list.append(el("li", "", path));
        }
        body.append(list);
    }
    const conflicts = Array.isArray(result.conflicts) ? result.conflicts : [];
    if (conflicts.length) {
        const box = el("div", "timeline-preview-conflicts");
        box.append(el("p", "", conflictTitle));
        const list = el("ul", "");
        for (const conflict of conflicts) {
            list.append(line("is-conflict", "!", `${conflict.path} · ${conflict.reason}`));
        }
        box.append(list);
        body.append(box);
    }
}

/**
 * 给请求加超时，避免回退请求悬挂时对话框永远停在 rewinding。
 *
 * @param {Promise<unknown>} promise 原请求
 * @param {number} ms 超时毫秒
 * @returns {Promise<unknown>} 原请求结果；超时则 reject
 */
function withTimeout(promise, ms) {
    let timer = 0;
    const timeout = new Promise((resolve, reject) => {
        timer = setTimeout(() => reject(new Error("操作超时，请检查网络后重试")), ms);
    });
    return Promise.race([promise, timeout]).finally(() => clearTimeout(timer));
}

async function confirmPreview() {
    const sessionId = state.session?.id;
    const isResume = previewMode === "resume";
    const target = isResume ? selectedBranch : selectedCheckpoint;
    if (!sessionId || !target || phase !== "previewing"
            || !previewResult?.restorable) {
        return;
    }
    // token 在 closePreview() 时递增，关闭对话框即可让飞行中的响应作废。
    const token = ++previewId;
    const note = String(dom.timelineNote?.value || "").trim();
    phase = "rewinding";
    dom.timelineConfirm.disabled = true;
    dom.timelineCancel.disabled = true;
    dom.timelinePreviewKicker.textContent = isResume ? "正在切换" : "正在回退";
    dom.timelinePreviewBody.replaceChildren(el("p", "timeline-preview-loading",
        isResume ? "正在还原文件并切换分支…" : "正在还原文件并把对话退回检查点…"));
    setPreviewError("");
    try {
        const payload = isResume
            ? { branchEntryId: target, dryRun: false, note }
            : { checkpointEntryId: target, dryRun: false, note };
        const result = isResume
            ? await withTimeout(api.resumeBranch(sessionId, payload), REWIND_TIMEOUT_MS)
            : await withTimeout(api.rewindSession(sessionId, payload), REWIND_TIMEOUT_MS);
        if (token !== previewId || state.session?.id !== sessionId) {
            return;
        }
        const done = isResume ? result.switched : result.rewound;
        if (!done) {
            previewResult = result;
            phase = "previewing";
            renderPreview(result);
            toast(result.conflicts?.length
                ? "有文件在目标之后被修改，未做任何改动"
                : (isResume ? "切换未执行" : "回退未执行"), true);
            return;
        }
        closePreview(true);
        phase = "ready";
        toast(isResume
            ? `已切到另一条分支，改动 ${(result.restored || []).length} 个文件`
            : `已回退，还原 ${(result.restored || []).length} 个文件`);
        try {
            await reloadHandler?.();
        } catch (error) {
            toast(isResume ? "切换完成，但刷新会话失败" : "回退完成，但刷新会话失败", true);
        }
        dom.input?.focus();
    } catch (error) {
        if (token !== previewId) {
            return;
        }
        // 超时或失败：退回预览态，允许重试或取消。
        phase = "previewing";
        dom.timelineCancel.disabled = false;
        dom.timelinePreviewKicker.textContent = previewResult?.restorable
            ? (isResume ? "切换预览" : "回退预览")
            : "存在冲突";
        setPreviewError(error.message || (isResume ? "切换失败" : "回退失败"));
        dom.timelineConfirm.disabled = !previewResult?.restorable;
    } finally {
        if (token === previewId) {
            dom.timelineCancel.disabled = false;
            if (phase === "previewing") {
                dom.timelineConfirm.disabled = !previewResult?.restorable;
            }
        }
    }
}

function closePreview(force = false) {
    if (phase === "rewinding" && !force) {
        return;
    }
    previewId++;
    if (dom.timelinePreview?.open) {
        dom.timelinePreview.close();
    }
    dom.timelinePreviewBody?.replaceChildren();
    setPreviewError("");
    if (dom.timelineNote) {
        dom.timelineNote.value = "";
    }
    if (dom.timelineConfirm) {
        dom.timelineConfirm.textContent =
            previewMode === "resume" ? "确认切换" : "确认回退";
        dom.timelineConfirm.disabled = true;
    }
    if (dom.timelineCancel) {
        dom.timelineCancel.disabled = false;
    }
    dom.timelinePreviewKicker.textContent =
        previewMode === "resume" ? "切换预览" : "回退预览";
    selectedCheckpoint = null;
    selectedBranch = null;
    previewMode = "rewind";
    previewResult = null;
    if (phase !== "closed") {
        phase = "ready";
    }
}

function setPreviewError(message) {
    if (!dom.timelinePreviewError) {
        return;
    }
    dom.timelinePreviewError.textContent = message || "";
    dom.timelinePreviewError.classList.toggle("hidden", !message);
}

function renderStatus(message, isError = false) {
    if (!dom.timelineStatus) {
        return;
    }
    dom.timelineStatus.textContent = message || "";
    dom.timelineStatus.classList.toggle("is-error", Boolean(isError));
}

function formatTime(value) {
    if (!value) {
        return "";
    }
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) {
        return "";
    }
    return date.toLocaleString("zh-CN", {
        month: "2-digit",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit"
    });
}

function shorten(text, limit) {
    const value = String(text || "");
    return value.length <= limit ? value : `${value.slice(0, limit - 1)}…`;
}
