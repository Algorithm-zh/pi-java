/**
 * 本轮文件改动面板。
 *
 * 只读展示最近一次 run 的逐轮 diff 与提交建议；撤销是显式动作，执行前必须
 * 二次确认。所有文件路径与 diff 文本都用 textContent 写入，不做 HTML 拼接。
 */

import * as api from "./api.js";
import { el } from "./dom.js";
import { dom, state, toast } from "./store.js";

let current = null;
let busy = false;
let reloadHandler = null;

/**
 * 注入撤销后的会话重载回调，避免模块循环依赖。
 *
 * @param {() => Promise<void>} handler 重载函数
 */
export function bindReload(handler) {
    reloadHandler = handler;
}

/**
 * 页面初始化：绑定展开与撤销按钮。
 */
export function initChanges() {
    dom.changesToggle?.addEventListener("click", toggleDetail);
    dom.changesUndo?.addEventListener("click", undo);
}

/**
 * 拉取当前会话最近的改动并重绘。
 */
export async function refresh() {
    const sessionId = state.session?.id;
    if (!sessionId) {
        reset();
        return;
    }
    try {
        const changes = await api.sessionChanges(sessionId);
        if (state.session?.id === sessionId) {
            render(changes);
        }
    } catch {
        hide();
    }
}

/**
 * 清空面板；切换或删除会话时调用。
 */
export function reset() {
    current = null;
    collapse();
    dom.changesDetail?.replaceChildren();
    hide();
}

function render(changes) {
    current = changes;
    if (!changes.available || !changes.turns.length) {
        hide();
        return;
    }
    dom.changesText.textContent = changes.branch
        ? `本轮改动 · ${changes.branch}` : "本轮改动";
    dom.changesStats.textContent = `${changes.fileCount} 个文件  +${changes.additions}  −${changes.deletions}`;
    dom.changesUndo.classList.toggle("hidden", !changes.canUndo);
    dom.changesUndo.disabled = false;
    dom.changesDock.classList.remove("hidden");
    renderDetail(changes);
}

function renderDetail(changes) {
    const detail = dom.changesDetail;
    detail.replaceChildren();
    const files = el("div", "changes-files");
    for (const turn of changes.turns) {
        for (const file of turn.files) {
            const row = el("div", "changes-file");
            row.append(el("span", `file-kind is-${String(file.kind).toLowerCase()}`,
                kindMark(file.kind)),
                el("code", "file-path", file.path),
                el("span", "file-stat", file.binary
                    ? "二进制" : `+${file.additions}  −${file.deletions}`));
            files.append(row);
        }
    }
    detail.append(files);
    if (changes.suggestedCommit) {
        detail.append(el("div", "changes-suggest", `建议提交：${changes.suggestedCommit}`));
    }
    for (const turn of changes.turns) {
        if (!turn.diff || !turn.diff.trim()) {
            continue;
        }
        detail.append(el("div", "diff-heading",
            `第 ${turn.turn} 轮${turn.truncated ? " · diff 已截断" : ""}`));
        detail.append(diffBlock(turn.diff));
    }
}

function diffBlock(text) {
    const pre = el("pre", "diff-view");
    const lines = text.endsWith("\n") ? text.slice(0, -1).split("\n") : text.split("\n");
    for (const line of lines) {
        // 每一行是块级元素，空行需要占位字符才能保留高度。
        pre.append(el("span", `diff-line ${diffKind(line)}`, line === "" ? " " : line));
    }
    return pre;
}

function diffKind(line) {
    if (line.startsWith("@@")) {
        return "is-hunk";
    }
    if (line.startsWith("+++") || line.startsWith("---") || line.startsWith("diff ")
            || line.startsWith("index ") || line.startsWith("new file")
            || line.startsWith("deleted file")) {
        return "is-meta";
    }
    if (line.startsWith("+")) {
        return "is-add";
    }
    if (line.startsWith("-")) {
        return "is-del";
    }
    return "";
}

function kindMark(kind) {
    switch (kind) {
        case "ADDED":
            return "A";
        case "DELETED":
            return "D";
        default:
            return "M";
    }
}

function toggleDetail() {
    const expanded = dom.changesToggle.getAttribute("aria-expanded") === "true";
    dom.changesToggle.setAttribute("aria-expanded", String(!expanded));
    dom.changesDetail.classList.toggle("hidden", expanded);
    dom.changesDock.classList.toggle("is-expanded", !expanded);
}

function collapse() {
    dom.changesToggle?.setAttribute("aria-expanded", "false");
    dom.changesDetail?.classList.add("hidden");
    dom.changesDock?.classList.remove("is-expanded");
}

function hide() {
    dom.changesDock?.classList.add("hidden");
    dom.changesUndo?.classList.add("hidden");
}

async function undo() {
    const changes = current;
    if (busy || !state.session || !changes?.canUndo) {
        return;
    }
    const confirmed = window.confirm(
        "撤销本轮？\n本轮改动的文件会还原到运行前，对话也会退回发送消息之前。");
    if (!confirmed) {
        return;
    }
    busy = true;
    dom.changesUndo.disabled = true;
    try {
        const result = await api.undoSession(state.session.id);
        if (!result.rewound) {
            showConflicts(result.conflicts);
            toast("有文件在本轮之后被修改，撤销未执行", true);
            return;
        }
        toast(`已撤销本轮，还原 ${result.restored.length} 个文件`);
        collapse();
        await reloadHandler?.();
    } catch (error) {
        toast(error.message || "撤销失败", true);
    } finally {
        busy = false;
        dom.changesUndo.disabled = false;
    }
}

function showConflicts(conflicts) {
    const detail = dom.changesDetail;
    detail.replaceChildren(el("div", "changes-conflict-title", "这些文件在本轮之后又被修改："));
    for (const conflict of conflicts) {
        const row = el("div", "changes-conflict");
        row.append(el("code", "file-path", conflict.path),
            el("span", "conflict-reason", conflict.reason));
        detail.append(row);
    }
    dom.changesDetail.classList.remove("hidden");
    dom.changesDock.classList.add("is-expanded");
    dom.changesToggle.setAttribute("aria-expanded", "true");
}
