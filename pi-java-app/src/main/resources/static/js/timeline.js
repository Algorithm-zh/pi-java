/**
 * 决策时间线：查看每次运行前的检查点与证据，并回退到任意检查点。
 *
 * 节点顺序、current 标记与 rewindable 全部由服务端决定，前端不自行推断分支；
 * 所有服务端文本都用 textContent 写入。
 */

import * as api from "./api.js";
import { el, stopReasonLabel } from "./dom.js";
import { dom, state, toast } from "./store.js";

let phase = "closed";
let timeline = null;
let loadedSessionId = null;
let selectedCheckpoint = null;
let previewResult = null;
let reloadHandler = null;
let requestId = 0;
let previewId = 0;

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
    dom.timelineToggle?.addEventListener("click", togglePanel);
    dom.timelineClose?.addEventListener("click", closePanel);
    dom.timelineSpine?.addEventListener("click", onSpineClick);
    dom.timelineList?.addEventListener("click", onListClick);
    dom.timelinePreviewClose?.addEventListener("click", () => closePreview());
    dom.timelineCancel?.addEventListener("click", () => closePreview());
    dom.timelinePreviewForm?.addEventListener("submit", (event) => {
        event.preventDefault();
        confirmRewind();
    });
    dom.timelinePreview?.addEventListener("cancel", (event) => {
        if (phase === "rewinding") {
            event.preventDefault();
        }
    });
    dom.timelinePreview?.addEventListener("close", () => {
        if (phase === "previewing") {
            closePreview(true);
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
    if (phase === "rewinding") {
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
    phase = "closed";
    timeline = null;
    loadedSessionId = null;
    closePanel();
    closePreview(true);
    dom.timelineList?.replaceChildren();
    dom.timelineSpine?.replaceChildren();
    dom.timelineSpine?.classList.add("hidden");
    renderStatus("");
    dom.timelineToggle?.classList.add("hidden");
}

function togglePanel() {
    if (dom.timelinePanel?.classList.contains("open")) {
        closePanel();
        return;
    }
    dom.timelinePanel?.classList.add("open");
    dom.timelinePanel?.setAttribute("aria-hidden", "false");
    dom.timelineToggle?.setAttribute("aria-expanded", "true");
    if (phase === "error" || loadedSessionId !== state.session?.id) {
        refresh();
    } else if (timeline) {
        render();
    }
}

function closePanel() {
    dom.timelinePanel?.classList.remove("open");
    dom.timelinePanel?.setAttribute("aria-hidden", "true");
    dom.timelineToggle?.setAttribute("aria-expanded", "false");
}

function showToggle() {
    dom.timelineToggle?.classList.remove("hidden");
}

function render() {
    const nodes = Array.isArray(timeline?.nodes) ? timeline.nodes : [];
    dom.timelineSubtitle.textContent = nodes.length
        ? `${nodes.length} 个决策点${timeline.available ? "" : " · 无 Git 检查点"}`
        : "还没有可回看的运行";
    dom.timelineList.replaceChildren();
    renderSpine(nodes);
    if (!nodes.length) {
        dom.timelineList.append(el("p", "timeline-empty",
            "还没有决策点，发送第一条消息后出现。"));
        renderStatus("");
        return;
    }
    for (const node of nodes) {
        dom.timelineList.append(renderNode(node));
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
        card.append(el("p", "timeline-brief", node.brief || "（无简报）"),
            el("span", "timeline-meta",
                `丢弃 ${node.discardedEntries || 0} 条记录 · `
                + `${node.discardedRuns || 0} 次运行`));
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
        if (node.type === "REWIND") {
            const mark = el("span", "timeline-tick is-rewind");
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
    const tick = event.target.closest("button[data-spine]");
    if (!tick) {
        return;
    }
    if (!dom.timelinePanel?.classList.contains("open")) {
        togglePanel();
    }
    const cards = Array.from(dom.timelineList?.querySelectorAll("[data-node]") || []);
    const target = cards.find((card) => card.dataset.node === tick.dataset.spine);
    target?.scrollIntoView({ block: "center", behavior: "smooth" });
}

function nodeTitle(node) {
    if (node.type === "REWIND") {
        return `回到 ${formatTime(node.at)} 之前`;
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
    const button = event.target.closest("button[data-checkpoint]");
    if (button) {
        preview(button.dataset.checkpoint);
    }
}

function nodeOf(checkpointEntryId) {
    return (timeline?.nodes || []).find((node) =>
        node.checkpointEntryId === checkpointEntryId) || null;
}

async function preview(checkpointEntryId) {
    const sessionId = state.session?.id;
    if (!sessionId || !checkpointEntryId
            || (phase !== "ready" && phase !== "previewing")) {
        return;
    }
    const token = ++previewId;
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
    body.replaceChildren();
    const node = nodeOf(selectedCheckpoint);
    dom.timelinePreviewTitle.textContent = node?.intent
        ? `回到「${shorten(node.intent, 48)}」之前`
        : "回到这次运行之前";
    dom.timelinePreviewKicker.textContent = result.restorable ? "回退预览" : "存在冲突";
    if (result.brief) {
        body.append(el("p", "timeline-preview-brief", result.brief));
    }
    const facts = el("div", "timeline-preview-facts");
    facts.append(el("span", "", `丢弃 ${result.discardedEntries || 0} 条记录`),
        el("span", "", `丢弃 ${result.discardedRuns || 0} 次运行`),
        el("span", "", `还原 ${(result.files || []).length} 个文件`));
    body.append(facts);
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
        box.append(el("p", "", "这些文件在目标之后又被修改，回退被整体放弃："));
        const list = el("ul", "");
        for (const conflict of conflicts) {
            list.append(line("is-conflict", "!", `${conflict.path} · ${conflict.reason}`));
        }
        box.append(list);
        body.append(box);
    }
    dom.timelineConfirm.disabled = !result.restorable;
    dom.timelineConfirm.textContent = result.restorable ? "确认回退" : "无法回退";
    setPreviewError("");
}

async function confirmRewind() {
    const sessionId = state.session?.id;
    if (!sessionId || !selectedCheckpoint || phase !== "previewing"
            || !previewResult?.restorable) {
        return;
    }
    const note = String(dom.timelineNote?.value || "").trim();
    phase = "rewinding";
    dom.timelineConfirm.disabled = true;
    dom.timelineCancel.disabled = true;
    dom.timelinePreviewKicker.textContent = "正在回退";
    dom.timelinePreviewBody.replaceChildren(el("p", "timeline-preview-loading",
        "正在还原文件并把对话退回检查点…"));
    setPreviewError("");
    try {
        const result = await api.rewindSession(sessionId, {
            checkpointEntryId: selectedCheckpoint,
            dryRun: false,
            note
        });
        if (!result.rewound) {
            previewResult = result;
            phase = "previewing";
            renderPreview(result);
            toast(result.conflicts?.length
                ? "有文件在目标之后被修改，未做任何改动"
                : "回退未执行", true);
            return;
        }
        closePreview(true);
        phase = "ready";
        toast(`已回退，还原 ${(result.restored || []).length} 个文件`);
        await reloadHandler?.();
        dom.input?.focus();
    } catch (error) {
        phase = "previewing";
        setPreviewError(error.message || "回退失败");
    } finally {
        dom.timelineCancel.disabled = false;
        if (phase === "previewing") {
            dom.timelineConfirm.disabled = !previewResult?.restorable;
        }
    }
}

function closePreview(force = false) {
    if (phase === "rewinding" && !force) {
        return;
    }
    if (dom.timelinePreview?.open) {
        dom.timelinePreview.close();
    }
    dom.timelinePreviewBody?.replaceChildren();
    setPreviewError("");
    if (dom.timelineNote) {
        dom.timelineNote.value = "";
    }
    if (dom.timelineConfirm) {
        dom.timelineConfirm.textContent = "确认回退";
        dom.timelineConfirm.disabled = true;
    }
    dom.timelinePreviewKicker.textContent = "回退预览";
    selectedCheckpoint = null;
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
