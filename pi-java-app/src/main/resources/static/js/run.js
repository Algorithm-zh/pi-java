/**
 * 一次 Agent run 的前端生命周期。
 *
 * 负责发送消息、解析 SSE、渲染流式文本与工具卡片、重连与取消；
 * 会话加载与头部状态由 session-view 维护。
 */

import * as api from "./api.js";
import * as approval from "./approval.js";
import * as changes from "./changes.js";
import * as contextMeter from "./context-meter.js";
import { el } from "./dom.js";
import { resetForConversation, renderUserBubble, stopReasonLabel } from "./render.js";
import { reloadSession, scrollToBottom, syncSessionTitle } from "./session-view.js";
import { dom, setRunState, state, toast } from "./store.js";
import { createStreamingTurn } from "./turn-view.js";
import * as usage from "./usage.js";

// 当前 DOM 正在跟随的事件流。用对象身份而非会话 ID 判断，避免“切走再切回”
// 时旧流的收尾逻辑把新连接当成自己的。
let attachment = null;

function attach(sessionId) {
    attachment = { sessionId };
    return attachment;
}

export async function start(text) {
    const sessionId = state.session.id;
    const token = attach(sessionId);
    approval.reset();
    const inner = resetForConversation(dom.messages);
    renderUserBubble(inner, text);
    state.inner = inner;
    state.turn = createStreamingTurn(inner);
    scrollToBottom(true);
    state.terminalSeen = false;
    state.controller = null;
    state.liveUsage = { input: 0, output: 0, totalTokens: 0 };
    state.modelName = state.session.modelProfileId;
    state.abortController = new AbortController();
    setStreamingUi(true);
    try {
        await api.streamMessage(sessionId, text, handleEvent, state.abortController.signal);
        if (!state.terminalSeen) {
            toast("事件流提前结束，正在按数据库同步会话", true);
        }
    } catch (error) {
        if (error.name !== "AbortError") {
            toast(error.message || "发送失败", true);
        }
    } finally {
        if (attachment === token) {
            attachment = null;
            state.controller?.finish();
            setStreamingUi(false);
            await reloadSession();
            syncQueue();
        }
    }
}

/**
 * 离开当前会话但不打断服务端运行：断开 SSE，回到侧栏去处理别的事，
 * 再次打开该会话时会自动重连并补齐进度。
 */
export function detach() {
    if (!attachment) {
        return;
    }
    attachment = null;
    const controller = state.abortController;
    state.abortController = null;
    controller?.abort();
    state.streaming = false;
    state.terminalSeen = false;
    state.controller = null;
    state.turn = null;
    dom.abortButton.classList.add("hidden");
    dom.sendButton.disabled = !state.session;
}

/**
 * 运行中把消息排进当前会话的队列，本轮结束后按顺序自动发送。
 *
 * @param {string} text 用户消息
 * @returns {boolean} 是否已入队
 */
export function enqueue(text) {
    const sessionId = state.session?.id;
    if (!sessionId || !text) {
        return false;
    }
    const queued = state.queued.get(sessionId) || [];
    queued.push(text);
    state.queued.set(sessionId, queued);
    renderQueue();
    return true;
}

/**
 * 同步排队提示，并在当前会话空闲时发出队首消息。
 */
export function syncQueue() {
    renderQueue();
    if (attachment || state.streaming || !state.session) {
        return;
    }
    const sessionId = state.session.id;
    if (state.session.status !== "IDLE") {
        return;
    }
    const queued = state.queued.get(sessionId);
    if (!queued || !queued.length) {
        return;
    }
    const text = queued.shift();
    if (queued.length) {
        state.queued.set(sessionId, queued);
    } else {
        state.queued.delete(sessionId);
    }
    start(text);
}

/**
 * 丢弃当前会话排队中的消息。
 */
export function cancelQueued() {
    if (!state.session) {
        return;
    }
    state.queued.delete(state.session.id);
    renderQueue();
}

function renderQueue() {
    const node = dom.composerQueue;
    if (!node) {
        return;
    }
    node.replaceChildren();
    const queued = state.session ? state.queued.get(state.session.id) : null;
    if (!queued || !queued.length) {
        node.classList.add("hidden");
        return;
    }
    const preview = queued[0].length > 60 ? `${queued[0].slice(0, 60)}…` : queued[0];
    node.append(el("span", "queue-label",
        queued.length > 1 ? `已排队 ${queued.length} 条` : "已排队"),
        el("span", "queue-text", preview));
    const cancel = el("button", "queue-cancel", "取消");
    cancel.type = "button";
    cancel.addEventListener("click", cancelQueued);
    node.append(cancel);
    node.classList.remove("hidden");
}

export async function reconnectRun(sessionId) {
    const token = attach(sessionId);
    state.streaming = true;
    state.terminalSeen = false;
    state.abortController = new AbortController();
    setStreamingUi(true);
    setRunState("检查运行状态…");
    try {
        const handle = await api.activeRun(sessionId);
        if (!handle) {
            // 进程重启后服务端已把遗留运行复位；重新加载以拿到真实的 IDLE 状态。
            toast("没有找到活跃运行，已刷新会话状态", true);
            return;
        }
        state.modelName = state.session?.modelProfileId || "";
        setRunState("重新连接运行…");
        await api.attachRun(handle.runId, handleReconnectEvent, state.abortController.signal);
        if (!state.terminalSeen) {
            toast("重连结束，正在同步会话", true);
        }
    } catch (error) {
        if (error.name !== "AbortError") {
            toast(error.message || "重连运行失败", true);
        }
    } finally {
        if (attachment === token) {
            attachment = null;
            setStreamingUi(false);
            await reloadSession();
            syncQueue();
        }
    }
}

export async function abort() {
    if (!state.session || !state.streaming) {
        return;
    }
    try {
        await api.abortSession(state.session.id);
        dom.abortButton.disabled = true;
        setRunState("正在停止…");
    } catch (error) {
        toast(error.message || "停止失败", true);
    }
}

function handleEvent({ name, payload }) {
    const type = payload.type || name;
    const data = payload.data || {};
    switch (type) {
        case "run.started":
            state.modelName = data.modelName || state.modelName;
            setRunState("生成中");
            syncSessionTitle();
            break;
        case "turn.started":
            state.turn?.setTurn(data.turn);
            setRunState(`第 ${data.turn} 轮`);
            break;
        case "message.started":
            state.controller = state.turn?.beginMessage(state.modelName) || null;
            break;
        case "message.delta":
            if (data.kind === "text") {
                state.controller?.text(data.text || "");
            } else if (data.kind === "thinking") {
                state.controller?.thinking(data.thinking || "");
            }
            scrollToBottom();
            break;
        case "message.completed":
            state.controller?.finish();
            accumulateUsage(data.usage);
            break;
        case "tool.started":
            state.turn?.toolStarted(data);
            scrollToBottom();
            break;
        case "tool.completed":
            state.turn?.toolCompleted(data);
            scrollToBottom();
            break;
        case "approval.requested":
            approval.requested(data);
            setRunState("等待审批");
            break;
        case "approval.resolved":
            approval.resolved(data);
            setRunState("生成中");
            break;
        case "run.completed":
        case "run.failed":
            finishRun(data, type === "run.failed");
            break;
        case "compaction.started":
            setRunState("压缩上下文");
            break;
        case "compaction.completed":
            setRunState("生成中");
            break;
        case "workspace.changed":
            changes.refresh();
            break;
        case "replay.gap":
            toast("事件回放不完整，将以数据库会话为准", true);
            break;
        default:
            break;
    }
}

function handleReconnectEvent({ name, payload }) {
    const type = payload.type || name;
    const data = payload.data || {};
    if (type === "turn.started") {
        setRunState(`第 ${data.turn} 轮`);
    } else if (type === "approval.requested") {
        approval.requested(data);
        setRunState("等待审批");
    } else if (type === "approval.resolved") {
        approval.resolved(data);
        setRunState("生成中");
    } else if (type === "run.completed" || type === "run.failed") {
        finishRun(data, type === "run.failed");
    } else if (type === "replay.gap") {
        toast("事件回放不完整，将以数据库会话为准", true);
    }
}

function finishRun(data, failed) {
    state.terminalSeen = true;
    approval.reset();
    state.controller?.finish();
    state.turn?.complete(data);
    dom.abortButton.classList.add("hidden");
    usage.show(data);
    const status = data.status || (failed ? "FAILED" : "COMPLETED");
    const label = status === "ABORTED" ? "已停止" : status === "FAILED" ? "失败" : "完成";
    setRunState(`${label} / ${stopReasonLabel(data.stopReason)}`, status === "FAILED");
    if (data.errorMessage) {
        toast(data.errorMessage, true);
    }
    changes.refresh();
}

function accumulateUsage(value) {
    if (!value) {
        return;
    }
    state.liveUsage.input += Number(value.input || 0);
    state.liveUsage.output += Number(value.output || 0);
    state.liveUsage.totalTokens += Number(value.totalTokens || 0);
    usage.showLive(state.liveUsage);
}

function setStreamingUi(streaming) {
    state.streaming = streaming;
    // 生成中仍可提交，消息会进入队列而不是再开一个 run。
    dom.sendButton.disabled = !state.session;
    dom.abortButton.classList.toggle("hidden", !streaming);
    dom.abortButton.disabled = false;
    contextMeter.sync();
    if (streaming) {
        setRunState("生成中");
    } else if (!state.terminalSeen) {
        dom.runState.classList.add("hidden");
    }
}
