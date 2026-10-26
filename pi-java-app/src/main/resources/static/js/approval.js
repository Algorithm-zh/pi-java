/**
 * 审批弹窗与待办队列。
 *
 * run SSE 推送 approval.requested 后，本模块按到达顺序串行展示待办，
 * 避免多个弹窗互相覆盖。决策通过 HTTP 提交；真正唤醒工具线程的是服务端
 * 事务提交后的广播，因此前端提交成功后只负责关闭当前项，不主动恢复 run。
 */

import * as api from "./api.js";
import { glyphOf } from "./render.js";
import { dom, state, toast } from "./store.js";
import { displayNameOf } from "./tools.js";

const RISK_LABELS = {
    LOW: "低风险",
    MEDIUM: "中风险",
    HIGH: "高风险",
    CRITICAL: "极高风险"
};
const queue = [];
let current = null;
let deciding = false;
let countdownTimer = null;

/**
 * 绑定弹窗交互。必须在 DOM 加载完成后调用。
 */
export function init() {
    dom.approvalDialog.addEventListener("cancel", (event) => event.preventDefault());
    dom.approvalApprove.addEventListener("click", () => decide("APPROVE"));
    dom.approvalDeny.addEventListener("click", () => decide("DENY"));
}

/**
 * 清空队列并关闭弹窗，切换会话或 run 结束时调用。
 */
export function reset() {
    queue.length = 0;
    current = null;
    deciding = false;
    closeDialog();
}

/**
 * 拉取会话的待审批请求，用于页面刷新或 SSE 断线后的兜底恢复。
 *
 * @param {string} sessionId 会话 ID
 */
export async function showPending(sessionId) {
    try {
        const pending = await api.listApprovals(sessionId, "PENDING");
        if (state.session?.id !== sessionId) {
            return;
        }
        for (const approval of pending) {
            enqueue(approval);
        }
    } catch (error) {
        toast(error.message || "加载待审批失败", true);
    }
}

/**
 * 处理 approval.requested 事件。
 *
 * @param {object} data SSE 数据
 */
export function requested(data) {
    if (!belongsToCurrentSession(data)) {
        return;
    }
    enqueue(data);
    api.getApproval(data.approvalId)
        .then(mergeDetail)
        .catch(() => {
            // 事件字段已足够展示，详情失败不阻塞决策。
        });
}

/**
 * 处理 approval.resolved 事件。
 *
 * @param {object} data SSE 数据
 */
export function resolved(data) {
    drop(data.approvalId);
}

function belongsToCurrentSession(data) {
    return Boolean(state.session && data?.sessionId === state.session.id);
}

function enqueue(approval) {
    if (!approval?.approvalId) {
        return;
    }
    if (current?.approvalId === approval.approvalId) {
        Object.assign(current, approval);
        renderCurrent(false);
        return;
    }
    const queued = queue.find((item) => item.approvalId === approval.approvalId);
    if (queued) {
        Object.assign(queued, approval);
        return;
    }
    queue.push(approval);
    if (current) {
        updateQueueLabel();
        return;
    }
    current = queue.shift();
    renderCurrent(true);
}

function mergeDetail(detail) {
    if (!detail?.approvalId) {
        return;
    }
    if (detail.status && detail.status !== "PENDING") {
        drop(detail.approvalId);
        return;
    }
    if (current?.approvalId === detail.approvalId) {
        Object.assign(current, detail);
        renderCurrent(false);
        return;
    }
    const queued = queue.find((item) => item.approvalId === detail.approvalId);
    if (queued) {
        Object.assign(queued, detail);
    }
}

function renderCurrent(resetReason) {
    if (!current) {
        closeDialog();
        return;
    }
    const risk = String(current.riskLevel || "HIGH");
    dom.approvalRisk.textContent = RISK_LABELS[risk] || risk;
    dom.approvalRisk.title = "风险等级由工具类型和参数自动评估";
    dom.approvalRisk.className = `risk-badge risk-${risk.toLowerCase()}`;
    dom.approvalToolName.textContent = displayNameOf(current.toolName) || "工具";
    dom.approvalToolName.title = current.toolName || "";
    dom.approvalGlyph.textContent = glyphOf(current.toolName);
    dom.approvalToolCall.textContent = current.toolCallId || "";
    dom.approvalArguments.textContent = formatArguments(current.arguments);
    dom.approvalDigest.textContent = current.argumentsDigest || "暂未获取";
    dom.approvalDigest.title = current.argumentsDigest || "";
    if (resetReason) {
        dom.approvalReason.value = "";
    }
    updateQueueLabel();
    scheduleCountdown();
    if (!dom.approvalDialog.open) {
        dom.approvalDialog.showModal();
        // 默认焦点放在“先不要”，避免误按回车直接批准。
        dom.approvalDeny.focus();
    }
}

async function decide(decision) {
    if (!current || deciding) {
        return;
    }
    deciding = true;
    setButtonsEnabled(false);
    const approvalId = current.approvalId;
    const toolName = displayNameOf(current.toolName) || "工具";
    try {
        await api.decideApproval(approvalId, decision, dom.approvalReason.value.trim());
        toast(decision === "APPROVE" ? `${toolName} 已批准` : `${toolName} 已拒绝`);
        drop(approvalId);
    } catch (error) {
        if (error.status === 404 || error.status === 409) {
            toast(error.message || "审批已失效", true);
            drop(approvalId);
        } else {
            toast(error.message || "提交审批失败", true);
        }
    } finally {
        deciding = false;
        if (current) {
            setButtonsEnabled(true);
            updateCountdown();
        }
    }
}

function drop(approvalId) {
    const index = queue.findIndex((item) => item.approvalId === approvalId);
    if (index >= 0) {
        queue.splice(index, 1);
    }
    if (current?.approvalId !== approvalId) {
        updateQueueLabel();
        return;
    }
    current = null;
    stopCountdown();
    if (queue.length) {
        current = queue.shift();
        renderCurrent(true);
        return;
    }
    closeDialog();
}

function scheduleCountdown() {
    stopCountdown();
    updateCountdown();
    if (current) {
        countdownTimer = setInterval(updateCountdown, 1000);
    }
}

function updateCountdown() {
    if (!current) {
        return;
    }
    const expires = Date.parse(current.expiresAt || "");
    if (Number.isNaN(expires)) {
        dom.approvalCountdown.textContent = "";
        return;
    }
    const remaining = expires - Date.now();
    if (remaining <= 0) {
        dom.approvalCountdown.textContent = "已过期";
        dom.approvalCountdown.classList.add("is-expired");
        setButtonsEnabled(false);
        stopCountdown();
        return;
    }
    dom.approvalCountdown.classList.remove("is-expired");
    const seconds = Math.ceil(remaining / 1000);
    const minutes = Math.floor(seconds / 60);
    dom.approvalCountdown.textContent =
        `剩余 ${minutes}:${String(seconds % 60).padStart(2, "0")}`;
    if (!deciding) {
        setButtonsEnabled(true);
    }
}

function stopCountdown() {
    if (countdownTimer) {
        clearInterval(countdownTimer);
        countdownTimer = null;
    }
}

function updateQueueLabel() {
    dom.approvalQueue.textContent = queue.length
        ? `队列中还有 ${queue.length} 个待审批`
        : "";
}

function setButtonsEnabled(enabled) {
    dom.approvalApprove.disabled = !enabled;
    dom.approvalDeny.disabled = !enabled;
}

function closeDialog() {
    stopCountdown();
    dom.approvalReason.value = "";
    dom.approvalQueue.textContent = "";
    dom.approvalCountdown.textContent = "";
    if (dom.approvalDialog.open) {
        dom.approvalDialog.close();
    }
}

function formatArguments(value) {
    if (!value || !Object.keys(value).length) {
        return "（无参数）";
    }
    try {
        return JSON.stringify(value, null, 2);
    } catch {
        return String(value);
    }
}
