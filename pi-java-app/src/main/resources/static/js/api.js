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

/**
 * 创建模型档案。请求只包含密钥环境变量名。
 *
 * @param {object} payload 档案与端点
 * @returns {Promise<object>} 新档案
 */
export function createModel(payload) {
    return request("/api/v1/models", jsonInit("POST", payload));
}

/**
 * 更新模型档案。
 *
 * @param {string} modelId 档案 ID
 * @param {object} payload 展示名与端点
 * @returns {Promise<object>} 更新后的档案
 */
export function updateModel(modelId, payload) {
    return request(`/api/v1/models/${encodeURIComponent(modelId)}`,
        jsonInit("PUT", payload));
}

/**
 * 启用或停用模型档案。
 *
 * @param {string} modelId 档案 ID
 * @param {boolean} enabled 是否启用
 * @returns {Promise<object>} 更新后的档案
 */
export function setModelEnabled(modelId, enabled) {
    return request(`/api/v1/models/${encodeURIComponent(modelId)}/${enabled ? "enable" : "disable"}`, {
        method: "POST",
        headers: { Accept: "application/json" }
    });
}

/**
 * 列出技能；refresh=true 时服务端重新扫描目录。
 *
 * @param {boolean} refresh 是否强制刷新
 * @returns {Promise<Array<object>>} 技能列表
 */
export function listSkills(refresh = false) {
    return request(`/api/v1/skills${refresh ? "?refresh=true" : ""}`);
}

/**
 * 读取技能正文。
 *
 * @param {string} name 技能名
 * @returns {Promise<{name: string, content: string}>} 正文
 */
export function getSkillContent(name) {
    return request(`/api/v1/skills/${encodeURIComponent(name)}/content`);
}

/**
 * 列出已安装插件。
 *
 * @returns {Promise<Array<object>>} 插件列表
 */
export function listPlugins() {
    return request("/api/v1/plugins");
}

/**
 * 按本地 jar 路径安装插件，安装后默认不加载。
 *
 * @param {string} jarPath jar 绝对路径
 * @returns {Promise<object>} 安装记录
 */
export function installPlugin(jarPath) {
    return request("/api/v1/plugins", jsonInit("POST", { jarPath }));
}

/**
 * 启用或停用插件。
 *
 * @param {string} pluginId 插件 ID
 * @param {boolean} enabled 是否启用
 * @returns {Promise<object>} 更新后的记录
 */
export function setPluginEnabled(pluginId, enabled) {
    return request(`/api/v1/plugins/${encodeURIComponent(pluginId)}/${enabled ? "enable" : "disable"}`, {
        method: "POST",
        headers: { Accept: "application/json" }
    });
}

/**
 * 列出工具策略。传入 cwd 时还会返回该工作区的规则。
 *
 * @param {string} cwd 工作目录，可为空
 * @returns {Promise<Array<object>>} 策略列表
 */
export function listToolPolicies(cwd) {
    const query = cwd ? `?cwd=${encodeURIComponent(cwd)}` : "";
    return request(`/api/v1/tool-policies${query}`);
}

/**
 * 新增或更新工具策略。
 *
 * @param {object} payload 策略
 * @returns {Promise<object>} 保存后的策略
 */
export function upsertToolPolicy(payload) {
    return request("/api/v1/tool-policies", jsonInit("PUT", payload));
}

/**
 * 列出工具目录，用于策略编辑器的工具选择。
 *
 * @returns {Promise<Array<object>>} 工具列表
 */
export function listTools() {
    return request("/api/v1/tools");
}

/**
 * 估算会话当前上下文用量。
 *
 * @param {string} sessionId 会话 ID
 * @returns {Promise<object>} 上下文状态
 */
export function contextStatus(sessionId) {
    return request(`/api/v1/sessions/${encodeURIComponent(sessionId)}/context`);
}

/**
 * 手动压缩会话上下文。
 *
 * @param {string} sessionId 会话 ID
 * @returns {Promise<object>} 压缩结果
 */
export function compactSession(sessionId) {
    return request(`/api/v1/sessions/${encodeURIComponent(sessionId)}/compact`, {
        method: "POST",
        headers: { Accept: "application/json" }
    });
}

/**
 * 重命名会话。
 *
 * @param {string} sessionId 会话 ID
 * @param {string} title 新标题
 * @returns {Promise<object>} 更新后的会话
 */
export function renameSession(sessionId, title) {
    return request(`/api/v1/sessions/${encodeURIComponent(sessionId)}`,
        jsonInit("PATCH", { title }));
}

/**
 * 切换会话模型。
 *
 * @param {string} sessionId 会话 ID
 * @param {string} modelProfileId 模型档案
 * @param {string} thinkingLevel 思考级别
 * @returns {Promise<object>} 更新后的会话
 */
export function changeSessionModel(sessionId, modelProfileId, thinkingLevel) {
    return request(`/api/v1/sessions/${encodeURIComponent(sessionId)}/model`,
        jsonInit("PUT", { modelProfileId, thinkingLevel }));
}

/**
 * 归档会话。
 *
 * @param {string} sessionId 会话 ID
 * @returns {Promise<object>} 更新后的会话
 */
export function archiveSession(sessionId) {
    return request(`/api/v1/sessions/${encodeURIComponent(sessionId)}/archive`, {
        method: "POST",
        headers: { Accept: "application/json" }
    });
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
    // 同时接受 JSON：run 启动前的领域错误会以 4xx JSON 返回，而不是 406。
    const response = await fetch(
        `/api/v1/sessions/${encodeURIComponent(sessionId)}/messages/stream`,
        {
            method: "POST",
            headers: {
                "Content-Type": "application/json",
                Accept: "application/json, text/event-stream"
            },
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
        headers: {
            Accept: "application/json, text/event-stream",
            "Last-Event-ID": `${runId}:0`
        },
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
