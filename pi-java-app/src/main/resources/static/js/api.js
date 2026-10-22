/**
 * pi-java Web UI 的 HTTP/SSE 客户端。
 *
 * 只依赖浏览器 fetch；所有路径都相对同源，避免 CORS 与硬编码主机。
 */

export class ApiError extends Error {
    constructor(message, code, status) {
        super(message);
        this.name = "ApiError";
        this.code = code;
        this.status = status;
    }
}

async function request(path, init = {}) {
    const response = await fetch(path, init);
    if (!response.ok) {
        throw await toError(response);
    }
    if (response.status === 204) {
        return null;
    }
    const type = response.headers.get("content-type") || "";
    return type.includes("application/json") ? response.json() : response.text();
}

async function toError(response) {
    try {
        const body = await response.json();
        return new ApiError(body.message || response.statusText, body.code, response.status);
    } catch {
        return new ApiError(`请求失败（HTTP ${response.status}）`, null, response.status);
    }
}

function jsonInit(method, body) {
    return {
        method,
        headers: { "Content-Type": "application/json", Accept: "application/json" },
        body: body === undefined ? undefined : JSON.stringify(body)
    };
}

export function systemInfo() {
    return request("/api/v1/system/info");
}

export function listModels() {
    return request("/api/v1/models");
}

export function listSessions() {
    return request("/api/v1/sessions?limit=50");
}

export function getSession(sessionId) {
    return request(`/api/v1/sessions/${encodeURIComponent(sessionId)}`);
}

export function listEntries(sessionId) {
    return request(`/api/v1/sessions/${encodeURIComponent(sessionId)}/entries`);
}

export function createSession(payload) {
    return request("/api/v1/sessions", jsonInit("POST", payload));
}

export function activeRun(sessionId) {
    return request(`/api/v1/sessions/${encodeURIComponent(sessionId)}/run`);
}

export function abortSession(sessionId) {
    return request(`/api/v1/sessions/${encodeURIComponent(sessionId)}/abort`, {
        method: "POST",
        headers: { Accept: "application/json" }
    });
}

/**
 * 查询会话下的审批，默认只取待处理项。
 *
 * @param {string} sessionId 会话 ID
 * @param {string} status 状态过滤，空字符串表示全部
 * @returns {Promise<Array<object>>} 审批列表
 */
export function listApprovals(sessionId, status = "PENDING") {
    const filter = status ? `&status=${encodeURIComponent(status)}` : "";
    return request(`/api/v1/approvals?sessionId=${encodeURIComponent(sessionId)}${filter}`);
}

/**
 * 查询单个审批详情，用于补齐 SSE 事件中省略的参数摘要。
 *
 * @param {string} approvalId 审批 ID
 * @returns {Promise<object>} 审批详情
 */
export function getApproval(approvalId) {
    return request(`/api/v1/approvals/${encodeURIComponent(approvalId)}`);
}

/**
 * 提交审批决策。
 *
 * @param {string} approvalId 审批 ID
 * @param {string} decision APPROVE 或 DENY
 * @param {string} reason 决策意见，可为空
 * @returns {Promise<object>} 终态审批
 */
export function decideApproval(approvalId, decision, reason) {
    return request(`/api/v1/approvals/${encodeURIComponent(approvalId)}/decision`,
        jsonInit("POST", { decision, reason: reason || undefined }));
}

/**
 * 发送消息并读取 POST SSE。
 *
 * @param {string} sessionId 会话 ID
 * @param {string} text 用户消息
 * @param {(event: {name: string, payload: object}) => void} onEvent 事件回调
 * @param {AbortSignal} signal 取消信号
 */
export async function streamMessage(sessionId, text, onEvent, signal) {
    const response = await fetch(
        `/api/v1/sessions/${encodeURIComponent(sessionId)}/messages/stream`,
        {
            method: "POST",
            headers: { "Content-Type": "application/json", Accept: "text/event-stream" },
            body: JSON.stringify({ text }),
            signal
        });
    if (!response.ok) {
        throw await toError(response);
    }
    await readSse(response, onEvent);
}

/**
 * 重连一个仍活跃的 run，从事件序号 0 开始回放。
 *
 * @param {string} runId 运行 ID
 * @param {(event: {name: string, payload: object}) => void} onEvent 事件回调
 * @param {AbortSignal} signal 取消信号
 */
export async function attachRun(runId, onEvent, signal) {
    const response = await fetch(`/api/v1/runs/${encodeURIComponent(runId)}/events`, {
        method: "GET",
        headers: { Accept: "text/event-stream", "Last-Event-ID": `${runId}:0` },
        signal
    });
    if (!response.ok) {
        throw await toError(response);
    }
    await readSse(response, onEvent);
}

async function readSse(response, onEvent) {
    if (!response.body) {
        throw new Error("当前浏览器不支持流式读取");
    }
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    while (true) {
        const { value, done } = await reader.read();
        if (done) {
            break;
        }
        buffer += decoder.decode(value, { stream: true });
        let boundary = findBoundary(buffer);
        while (boundary) {
            const frame = buffer.slice(0, boundary.index);
            buffer = buffer.slice(boundary.index + boundary.length);
            deliver(frame, onEvent);
            boundary = findBoundary(buffer);
        }
    }
    buffer += decoder.decode();
    if (buffer.trim()) {
        deliver(buffer, onEvent);
    }
}

function findBoundary(buffer) {
    const match = /\r?\n\r?\n/.exec(buffer);
    return match ? { index: match.index, length: match[0].length } : null;
}

function deliver(frame, onEvent) {
    let name = "message";
    const data = [];
    for (const line of frame.split(/\r?\n/)) {
        if (!line || line.startsWith(":")) {
            continue;
        }
        const colon = line.indexOf(":");
        const field = colon === -1 ? line : line.slice(0, colon);
        let value = colon === -1 ? "" : line.slice(colon + 1);
        if (value.startsWith(" ")) {
            value = value.slice(1);
        }
        if (field === "event") {
            name = value;
        } else if (field === "data") {
            data.push(value);
        }
    }
    if (!data.length) {
        return;
    }
    try {
        onEvent({ name, payload: JSON.parse(data.join("\n")) });
    } catch {
        // 单帧解析失败不应中断整条流；后续事件仍然可用。
    }
}
