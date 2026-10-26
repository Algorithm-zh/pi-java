/**
 * 待办与自动验证面板。
 *
 * 展示当前分支最新的待办快照和最近一次自动验证；运行中由 SSE 事件直接更新，
 * 结束后再从数据库取完整结果。所有内容都通过 textContent 写入。
 */

import * as api from "./api.js";
import { el, formatDuration } from "./dom.js";
import { dom, state } from "./store.js";

const STATUS_CLASS = {
    done: "is-done",
    in_progress: "is-active",
    pending: "is-pending"
};

let current = null;

/**
 * 绑定展开/收起交互。页面加载后调用一次。
 */
export function initPlan() {
    dom.planToggle?.addEventListener("click", toggleDetail);
}

/**
 * 拉取当前会话的待办与最近一次验证。
 */
export async function refresh() {
    const sessionId = state.session?.id;
    if (!sessionId) {
        reset();
        return;
    }
    try {
        const data = await api.sessionPlan(sessionId);
        if (state.session?.id !== sessionId) {
            return;
        }
        current = {
            items: Array.isArray(data.items) ? data.items : [],
            total: Number(data.total || 0),
            completed: Number(data.completed || 0),
            verification: data.verification || null
        };
        render();
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
    dom.planDetail?.replaceChildren();
    hide();
}

/**
 * 直接用 plan.updated 事件的数据重绘，避免每个事件都请求接口。
 *
 * @param {object} data 事件数据
 */
export function applyEvent(data) {
    current = {
        items: Array.isArray(data.items) ? data.items : [],
        total: Number(data.total || 0),
        completed: Number(data.completed || 0),
        verification: current?.verification || null
    };
    render();
}

/**
 * 用 verification.completed 事件的数据更新验证结果。
 *
 * @param {object} data 事件数据
 */
export function applyVerification(data) {
    current = {
        items: current?.items || [],
        total: current?.total || 0,
        completed: current?.completed || 0,
        verification: {
            command: data.command || "",
            passed: Boolean(data.passed),
            exitCode: data.exitCode === undefined ? null : data.exitCode,
            timedOut: Boolean(data.timedOut),
            truncated: Boolean(data.truncated),
            durationMs: Number(data.durationMs || 0),
            output: data.output || ""
        }
    };
    render();
}

function render() {
    const items = Array.isArray(current?.items) ? current.items : [];
    const verification = current?.verification || null;
    const hasPlan = items.length > 0;
    if (!hasPlan && !verification) {
        hide();
        return;
    }
    const total = hasPlan ? Math.max(current.total || 0, items.length) : 0;
    const completed = hasPlan ? Math.min(current.completed || 0, total) : 0;
    const percent = total ? Math.round((completed / total) * 100) : 0;
    dom.planDock.classList.remove("hidden");
    dom.planDock.classList.toggle("is-verify-only", !hasPlan);
    dom.planDock.classList.toggle("is-done", hasPlan && completed >= total);
    dom.planDock.classList.toggle("is-failed",
        Boolean(verification && !verification.passed));
    dom.planTitle.textContent = hasPlan ? "任务待办" : "自动验证";
    dom.planSeal.style.setProperty("--plan-progress", String(percent));
    dom.planSealText.textContent = `${percent}%`;
    dom.planRailFill.style.width = `${percent}%`;
    dom.planCount.textContent = hasPlan ? `${completed}/${total}` : "";
    dom.planCurrent.textContent = hasPlan
        ? currentLine(items) : verification?.command || "";
    renderVerifyChip(verification);
    renderDetail(items, verification);
}

function renderVerifyChip(verification) {
    if (!verification) {
        dom.planVerifyChip.classList.add("hidden");
        return;
    }
    dom.planVerifyChip.textContent = verification.passed ? "验证通过" : "验证未通过";
    dom.planVerifyChip.classList.toggle("is-passed", verification.passed);
    dom.planVerifyChip.classList.toggle("is-failed", !verification.passed);
    dom.planVerifyChip.classList.remove("hidden");
}

function renderDetail(items, verification) {
    const detail = dom.planDetail;
    detail.replaceChildren();
    if (items.length) {
        const list = el("ol", "plan-items");
        for (const item of items) {
            list.append(planRow(item));
        }
        detail.append(list);
    }
    if (verification) {
        detail.append(verificationBlock(verification));
    }
}

function planRow(item) {
    const status = statusOf(item);
    const mark = status === "done" ? "✓" : status === "in_progress" ? "●" : "";
    const row = el("li", `plan-item ${STATUS_CLASS[status]}`);
    row.append(el("span", "plan-item-mark", mark),
        el("span", "plan-item-text", item.content || ""));
    return row;
}

function verificationBlock(verification) {
    const box = document.createElement("details");
    box.className = `plan-verify ${verification.passed ? "is-passed" : "is-failed"}`;
    const summary = document.createElement("summary");
    summary.append(
        el("span", "verify-mark", verification.passed ? "✓" : "×"),
        el("span", "verify-label",
            verification.passed ? "自动验证通过" : "自动验证未通过"),
        el("span", "verify-meta", verifyMeta(verification)));
    box.append(summary, el("pre", "verify-output",
        verification.output || "（无输出）"));
    return box;
}

function verifyMeta(verification) {
    const parts = [verification.command || "未设置命令"];
    if (verification.timedOut) {
        parts.push("执行超时");
    } else if (verification.exitCode !== null && verification.exitCode !== undefined) {
        parts.push(`退出码 ${verification.exitCode}`);
    }
    parts.push(formatDuration(verification.durationMs));
    return parts.join(" · ");
}

function currentLine(items) {
    const active = items.find((item) => statusOf(item) === "in_progress");
    if (active) {
        return active.content || "";
    }
    const pending = items.find((item) => statusOf(item) === "pending");
    if (pending) {
        return pending.content || "";
    }
    return "全部完成";
}

function statusOf(item) {
    const status = String(item?.status || "pending");
    return status === "done" || status === "in_progress" ? status : "pending";
}

function toggleDetail() {
    const expanded = dom.planToggle.getAttribute("aria-expanded") === "true";
    dom.planToggle.setAttribute("aria-expanded", String(!expanded));
    dom.planDetail.classList.toggle("hidden", expanded);
    dom.planDock.classList.toggle("is-expanded", !expanded);
}

function collapse() {
    dom.planToggle?.setAttribute("aria-expanded", "false");
    dom.planDetail?.classList.add("hidden");
    dom.planDock?.classList.remove("is-expanded");
}

function hide() {
    dom.planDock?.classList.add("hidden");
    dom.planVerifyChip?.classList.add("hidden");
    dom.planDetail?.classList.add("hidden");
    dom.planDock?.classList.remove("is-expanded");
}
