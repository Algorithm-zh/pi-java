/**
 * 前端共享的 DOM、格式化与标签工具。
 *
 * 所有来自服务端/模型的文本仍由 textContent 写入；本模块不拼 HTML。
 */

export function el(tag, className, text) {
    const node = document.createElement(tag);
    if (className) {
        node.className = className;
    }
    if (text !== undefined && text !== null) {
        node.textContent = String(text);
    }
    return node;
}

const STATUS_LABELS = {
    IDLE: "空闲",
    RUNNING: "运行中",
    WAITING_APPROVAL: "等待审批",
    ARCHIVED: "已归档"
};

const STOP_LABELS = {
    STOP: "完成",
    LENGTH: "输出达到上限",
    MAX_STEPS: "达到步数上限",
    CANCELLED: "已取消",
    TIMEOUT: "超时",
    ERROR: "失败"
};

export function statusLabel(status) {
    return STATUS_LABELS[status] || status || "";
}

export function stopReasonLabel(reason) {
    return STOP_LABELS[reason] || reason || "";
}

export function basename(path) {
    if (!path) {
        return "";
    }
    const parts = String(path).split(/[\\/]/).filter(Boolean);
    return parts.length ? parts[parts.length - 1] : path;
}

export function formatNumber(value) {
    return new Intl.NumberFormat("zh-CN").format(Number(value || 0));
}

export function formatDuration(ms) {
    const value = Number(ms || 0);
    if (value < 1000) {
        return `${value} ms`;
    }
    if (value < 60_000) {
        return `${(value / 1000).toFixed(1)} s`;
    }
    return `${Math.floor(value / 60_000)} 分 ${Math.round((value % 60_000) / 1000)} 秒`;
}

export function glyphOf(toolName) {
    const name = String(toolName || "");
    const known = {read: "RD", ls: "LS", grep: "GR", glob: "GL", bash: "SH", write: "WR", edit: "ED"};
    if (known[name]) {
        return known[name];
    }
    return name.slice(0, 2).toUpperCase() || "T";
}

export function summarizeArgs(args) {
    if (!args || !Object.keys(args).length) {
        return "";
    }
    const text = Object.entries(args)
        .map(([key, value]) => `${key}=${typeof value === "string" ? value : JSON.stringify(value)}`)
        .join(" / ");
    return text.length > 110 ? `${text.slice(0, 110)}…` : text;
}

/**
 * 把 assistant/tool 的内容块拼成纯文本。
 *
 * @param {Array<object>} blocks 内容块
 * @returns {string} 文本
 */
export function blocksText(blocks) {
    return (blocks || [])
        .filter((block) => block.type === "text")
        .map((block) => block.text || "")
        .filter(Boolean)
        .join("\n");
}

export function timeOf(entry) {
    if (!entry.createdAt) {
        return "";
    }
    const date = new Date(entry.createdAt);
    if (Number.isNaN(date.getTime())) {
        return "";
    }
    return date.toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit" });
}
