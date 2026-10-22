/**
 * 一次 Agent run 的前端生命周期。
 *
 * 负责发送消息、解析 SSE、渲染流式文本与工具卡片、重连与取消；
 * 会话加载与头部状态由 session-view 维护。
 */

import * as api from "./api.js";
import * as approval from "./approval.js";
import {
    beginAssistant, resetForConversation, renderUserBubble, stopReasonLabel, upsertToolCard
} from "./render.js";
import { reloadSession, scrollToBottom, updateHeader } from "./session-view.js";
import { dom, setRunState, state, toast } from "./store.js";
import * as usage from "./usage.js";

export async function start(text) {
    approval.reset();
    const inner = resetForConversation(dom.messages);
    renderUserBubble(inner, text);
    state.inner = inner;
    scrollToBottom(true);
    state.terminalSeen = false;
    state.controller = null;
    state.liveUsage = { input: 0, output: 0, totalTokens: 0 };
    state.modelName = state.session.modelProfileId;
    state.abortController = new AbortController();
    setStreamingUi(true);
    try {
        await api.streamMessage(state.session.id, text, handleEvent, state.abortController.signal);
        if (!state.terminalSeen) {
            toast("事件流提前结束，正在按数据库同步会话", true);
        }
    } catch (error) {
        if (error.name !== "AbortError") {
            toast(error.message || "发送失败", true);
        }
    } finally {
        state.controller?.finish();
        setStreamingUi(false);
        await reloadSession();
    }
}

export async function reconnectRun(sessionId) {
    state.streaming = true;
    state.terminalSeen = false;
    state.abortController = new AbortController();
    setStreamingUi(true);
    setRunState("检查运行状态…");
    let attached = false;
    try {
        const handle = await api.activeRun(sessionId);
        if (!handle) {
            toast("没有找到活跃运行，已刷新会话状态", true);
            return;
        }
        attached = true;
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
        setStreamingUi(false);
        if (attached) {
            await reloadSession();
        } else {
            updateHeader();
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
            break;
        case "turn.started":
            setRunState(`第 ${data.turn} 轮`);
            break;
        case "message.started":
            state.controller = beginAssistant(state.inner, state.modelName);
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
            state.controller?.finish();
            upsertToolCard(state.controller?.body || state.inner, {
                callId: data.callId,
                toolName: data.toolName,
                args: data.arguments,
                pending: true
            });
            scrollToBottom();
            break;
        case "tool.completed":
            upsertToolCard(state.inner, {
                callId: data.callId,
                toolName: data.toolName,
                args: null,
                content: data.content,
                isError: Boolean(data.isError),
                pending: false
            });
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
    dom.abortButton.classList.add("hidden");
    usage.show(data);
    const status = data.status || (failed ? "FAILED" : "COMPLETED");
    const label = status === "ABORTED" ? "已停止" : status === "FAILED" ? "失败" : "完成";
    setRunState(`${label} · ${stopReasonLabel(data.stopReason)}`, status === "FAILED");
    if (data.errorMessage) {
        toast(data.errorMessage, true);
    }
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
    dom.sendButton.disabled = streaming || !state.session;
    dom.abortButton.classList.toggle("hidden", !streaming);
    dom.abortButton.disabled = false;
    if (streaming) {
        setRunState("生成中");
    } else if (!state.terminalSeen) {
        dom.runState.classList.add("hidden");
    }
}
