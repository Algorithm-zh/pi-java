/**
 * 工具目录的展示层信息。
 *
 * 服务端的工具名是稳定标识（内置名、插件前缀、MCP 命名空间），不适合直接
 * 展示给普通用户；本模块把它们映射成中文名称，并根据 JSON Schema 提供
 * 参数标签，供 /tool 选择器、参数表单与消息渲染复用。
 */

import * as api from "./api.js";

const BUILT_INS = {
    read: {name: "读取文件", description: "查看工作区里的文件内容"},
    ls: {name: "列出目录", description: "浏览目录里有哪些文件"},
    glob: {name: "查找文件", description: "按文件名模式搜索文件"},
    grep: {name: "搜索内容", description: "在文件内容里搜索关键词或正则"},
    write: {name: "写入文件", description: "创建或覆盖文件（运行前需要审批）"},
    edit: {name: "修改文件", description: "替换文件中的指定内容（运行前需要审批）"},
    bash: {name: "运行命令", description: "执行终端命令（运行前需要审批）"},
    subagent: {name: "调用子代理", description: "把独立任务交给子代理处理"}
};

const FIELD_LABELS = {
    path: "文件路径",
    content: "文件内容",
    command: "命令",
    cwd: "工作目录",
    timeoutSeconds: "超时（秒）",
    pattern: "匹配模式",
    glob: "文件名过滤",
    ignoreCase: "忽略大小写",
    maxResults: "最多结果数",
    depth: "目录深度",
    offset: "起始行",
    limit: "行数",
    oldText: "原内容",
    newText: "新内容",
    replaceAll: "替换全部",
    task: "任务描述",
    mode: "运行模式",
    persona: "角色设定",
    tools: "可用工具",
    text: "文本"
};

const RISK_LABELS = {LOW: "低风险", MEDIUM: "中风险", HIGH: "高风险", CRITICAL: "危险"};

let catalog = null;
let loading = null;

/**
 * 加载工具目录与插件清单，失败时返回空目录。
 *
 * @param {boolean} force 是否强制刷新
 * @returns {Promise<Array<object>>} 工具条目
 */
export async function load(force = false) {
    if (catalog && !force) {
        return catalog;
    }
    if (loading) {
        return loading;
    }
    loading = (async () => {
        try {
            const [tools, plugins] = await Promise.all([
                api.listTools(),
                api.listPlugins().catch(() => [])
            ]);
            catalog = buildCatalog(tools, plugins);
        } catch {
            catalog = [];
        }
        return catalog;
    })();
    try {
        return await loading;
    } finally {
        loading = null;
    }
}

/**
 * 让下一次访问重新加载目录。
 */
export function invalidate() {
    catalog = null;
}

/**
 * 当前缓存的工具条目；未加载时为空数组。
 *
 * @returns {Array<object>} 工具条目
 */
export function current() {
    return catalog || [];
}

/**
 * 目录是否已经加载过一次（即使结果为空）。
 *
 * @returns {boolean} 是否已加载
 */
export function isLoaded() {
    return catalog !== null;
}

/**
 * 按完整工具名查找。
 *
 * @param {string} name 工具名
 * @returns {object|undefined} 工具条目
 */
export function find(name) {
    return current().find((tool) => tool.name === name);
}

/**
 * 工具的中文展示名；未知工具退回原名。
 *
 * @param {string} name 工具名
 * @returns {string} 展示名
 */
export function displayNameOf(name) {
    const tool = find(name);
    if (tool) {
        return tool.displayName;
    }
    const builtIn = BUILT_INS[name];
    if (builtIn) {
        return builtIn.name;
    }
    const plugin = pluginOf(name);
    if (plugin) {
        return plugin.name || name;
    }
    return name || "工具";
}

/**
 * 参数标签：优先 schema title，其次通用字典，最后取描述首句。
 *
 * @param {string} key 参数名
 * @param {object} property JSON Schema 属性
 * @returns {string} 标签
 */
export function fieldLabel(key, property = {}) {
    if (property && property.title) {
        return String(property.title);
    }
    if (FIELD_LABELS[key]) {
        return FIELD_LABELS[key];
    }
    if (property && property.description) {
        const sentence = String(property.description).split(/[，,。；;]/)[0].trim();
        if (sentence) {
            return sentence;
        }
    }
    return key;
}

/**
 * 参数帮助文本；与标签相同时返回空串。
 *
 * @param {string} key 参数名
 * @param {object} property JSON Schema 属性
 * @returns {string} 帮助
 */
export function fieldHelp(key, property = {}) {
    const description = property && property.description ? String(property.description).trim() : "";
    return description && description !== fieldLabel(key, property) ? description : "";
}

/**
 * 风险标签。
 *
 * @param {string} risk 风险枚举
 * @returns {string} 标签
 */
export function riskLabel(risk) {
    return RISK_LABELS[risk] || risk || "";
}

/**
 * 把 /tool 命令解析成展示信息；不是工具命令时返回 null。
 *
 * @param {string} text 用户消息
 * @returns {object|null} 展示信息
 */
export function describeCommand(text) {
    const value = String(text || "").trim();
    const match = /^\/tool\s+(\S+)(?:\s+([\s\S]+))?$/.exec(value);
    if (!match) {
        return null;
    }
    const tool = find(match[1]);
    let args = {};
    if (match[2]) {
        try {
            const parsed = JSON.parse(match[2]);
            if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) {
                args = parsed;
            }
        } catch {
            // 参数不是合法 JSON 时只展示工具名。
        }
    }
    return {
        toolName: match[1],
        displayName: displayNameOf(match[1]),
        args,
        summary: summarizeFields(tool, args),
        raw: value
    };
}

function buildCatalog(tools, plugins) {
    const knownPlugins = (plugins || [])
        .filter((plugin) => plugin && plugin.id)
        .sort((left, right) => right.id.length - left.id.length);
    const counts = new Map();
    for (const tool of tools) {
        const plugin = matchPlugin(tool.name, knownPlugins);
        if (plugin) {
            counts.set(plugin.id, (counts.get(plugin.id) || 0) + 1);
        }
    }
    const catalog = (tools || []).map((tool) => {
        const plugin = matchPlugin(tool.name, knownPlugins);
        let schema = null;
        try {
            schema = JSON.parse(tool.inputSchema || "{}");
        } catch {
            schema = null;
        }
        const builtIn = BUILT_INS[tool.name];
        const shortName = plugin ? tool.name.slice(plugin.id.length + 1) : "";
        let displayName = tool.name;
        let description = tool.description || "";
        let group = "MCP 工具";
        if (builtIn) {
            displayName = builtIn.name;
            description = builtIn.description;
            group = "内置工具";
        } else if (plugin) {
            displayName = (counts.get(plugin.id) || 0) > 1
                ? `${plugin.name || plugin.id} · ${shortName}`
                : (plugin.name || plugin.id);
            description = tool.description || plugin.description || "";
            group = `插件 · ${plugin.name || plugin.id}`;
        }
        return {
            name: tool.name,
            shortName,
            displayName,
            description,
            schema,
            risk: tool.risk,
            requiresApproval: Boolean(tool.requiresApproval),
            pluginId: plugin ? plugin.id : "",
            pluginName: plugin ? (plugin.name || plugin.id) : "",
            group,
            search: `${displayName} ${tool.name} ${shortName} ${description} ${plugin ? plugin.name : ""}`
                .toLowerCase()
        };
    });
    // /tool 菜单按分组连续展示，避免“内置工具”标题被插件条目切断。
    return catalog.sort((left, right) => groupRank(left.group) - groupRank(right.group)
        || left.group.localeCompare(right.group, "zh"));
}

function groupRank(group) {
    if (group === "内置工具") {
        return 0;
    }
    return group.startsWith("插件") ? 1 : 2;
}

function matchPlugin(toolName, plugins) {
    return plugins.find((plugin) => String(toolName).startsWith(`${plugin.id}.`));
}

function pluginOf(toolName) {
    const name = String(toolName || "");
    const entry = current().find((tool) => tool.name === name && tool.pluginId);
    if (entry) {
        return {id: entry.pluginId, name: entry.pluginName};
    }
    return null;
}

function summarizeFields(tool, args) {
    const properties = tool && tool.schema && tool.schema.properties ? tool.schema.properties : {};
    const parts = [];
    for (const [key, value] of Object.entries(args || {})) {
        const label = fieldLabel(key, properties[key] || {});
        let shown;
        if (typeof value === "boolean") {
            shown = value ? "是" : "否";
        } else if (value !== null && typeof value === "object") {
            shown = JSON.stringify(value);
        } else {
            shown = String(value);
        }
        if (shown.length > 40) {
            shown = `${shown.slice(0, 40)}…`;
        }
        parts.push(`${label}：${shown}`);
    }
    const summary = parts.join(" · ");
    return summary.length > 110 ? `${summary.slice(0, 110)}…` : summary;
}
