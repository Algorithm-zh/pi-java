/**
 * 顶部上下文仪表与手动压缩。
 *
 * 只依赖状态与 API，不直接操作会话视图；压缩完成后通过注入的回调
 * 重新加载会话，保持模块单向依赖。
 */

import * as api from "./api.js";
import { formatNumber } from "./render.js";
import { dom, state, toast } from "./store.js";

let reloadHandler = null;
let requestToken = 0;
let compacting = false;

/**
 * 绑定压缩按钮。
 *
 * @param {{onReload?: Function}} options 压缩完成后的重载回调
 */
export function init(options = {}) {
    reloadHandler = options.onReload || null;
    dom.compactButton.addEventListener("click", compact);
    updateCompactButton();
}

/**
 * 重新读取并渲染当前会话的上下文用量。
 */
export async function refresh() {
    const session = state.session;
    const token = ++requestToken;
    if (!session) {
        hide();
        return;
    }
    updateCompactButton();
    try {
        const status = await api.contextStatus(session.id);
        if (token !== requestToken || state.session?.id !== session.id) {
            return;
        }
        renderMeter(status);
    } catch {
        if (token === requestToken) {
            hide();
        }
    }
}

/**
 * 同步压缩按钮的可用状态，供运行状态变化时调用。
 */
export function sync() {
    updateCompactButton();
}

/**
 * 手动压缩当前会话，仅空闲状态可用。
 */
export async function compact() {
    if (!state.session || state.streaming || compacting) {
        return;
    }
    compacting = true;
    updateCompactButton();
    try {
        const result = await api.compactSession(state.session.id);
        if (result.applied) {
            toast(`上下文已压缩，节省 ${formatNumber(result.savedTokens)} tokens`);
            if (reloadHandler) {
                await reloadHandler();
            } else {
                await refresh();
            }
        } else {
            toast(result.message || "当前上下文无需压缩");
        }
    } catch (error) {
        toast(error.message || "压缩失败", true);
    } finally {
        compacting = false;
        updateCompactButton();
    }
}

function renderMeter(status) {
    const windowSize = Math.max(1, Number(status.contextWindow || 0));
    const tokens = Math.max(0, Number(status.tokens || 0));
    const percent = Math.min(100, Math.round((tokens / windowSize) * 100));
    dom.contextFill.style.width = `${Math.max(percent, 1)}%`;
    dom.contextFill.classList.toggle("is-warn", percent >= 70 && percent < 88);
    dom.contextFill.classList.toggle("is-hot", percent >= 88);
    dom.contextLabel.textContent =
        `${formatNumber(tokens)} / ${formatNumber(windowSize)} (${percent}%)`;
    dom.contextWidget.title = [
        `模型：${status.modelName || status.modelProfileId || "未知"}`,
        `Provider：${status.provider || "未知"}`,
        `预留输出：${formatNumber(status.maxOutputTokens)} tokens`,
        `可用：${formatNumber(status.availableTokens)} tokens`
    ].join("\n");
    dom.contextWidget.classList.remove("hidden");
    updateCompactButton();
}

function updateCompactButton() {
    const archived = state.session?.status === "ARCHIVED";
    dom.compactButton.disabled = !state.session || state.streaming || compacting || archived;
    dom.compactButton.textContent = compacting ? "压缩中…" : "压缩";
}

function hide() {
    dom.contextWidget.classList.add("hidden");
    dom.contextFill.style.width = "0%";
}
