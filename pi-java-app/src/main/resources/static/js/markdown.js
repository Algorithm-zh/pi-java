/**
 * 轻量 Markdown 渲染器。
 *
 * 只把 Markdown 解析为 DOM 节点，全程使用 textContent，禁止 innerHTML。
 * 支持标题、段落、列表、引用、代码块、表格、分割线与常见行内语法；
 * 链接仅允许 http/https/mailto，避免模型输出 javascript: 等危险协议。
 */

import { el } from "./dom.js";

const SAFE_PROTOCOLS = new Set(["http:", "https:", "mailto:"]);
const LIST_RE = /^(\s*)(?:([-*+])|(\d{1,9})[.)])\s+(.*)$/;
const FENCE_RE = /^ {0,3}(```+|~~~+)\s*([^\s`]*)?\s*$/;
const HEADING_RE = /^ {0,3}(#{1,6})\s+(.*?)\s*#*\s*$/;
const RULE_RE = /^ {0,3}([-*_])(?:\s*\1){2,}\s*$/;
const QUOTE_RE = /^ {0,3}>\s?(.*)$/;
const TABLE_SEP_RE = /^:?-{3,}:?$/;

const INLINE_RULES = [
    {type: "code", re: /`([^`\n]+)`/g},
    {type: "link", re: /\[([^\]\n]+)\]\(([^)\s]+)(?:\s+"[^"]*")?\)/g},
    {type: "image", re: /!\[([^\]\n]*)\]\(([^)\s]+)\)/g},
    {type: "strong", re: /\*\*([^*\n]+)\*\*/g},
    {type: "strong", re: /__([^_\n]+)__/g},
    {type: "del", re: /~~([^~\n]+)~~/g},
    {type: "em", re: /(?<!\*)\*([^*\n]+)\*(?!\*)/g},
    {type: "em", re: /(?<![\w_])_([^_\n]+)_(?![\w_])/g},
    {type: "autolink", re: /https?:\/\/[^\s<>()]+[^\s<>().,;:!?]/g}
];

/**
 * 把 Markdown 文本渲染到容器中，替换原有内容。
 *
 * @param {HTMLElement} container 目标容器
 * @param {string} text Markdown 文本
 */
export function renderMarkdownInto(container, text) {
    container.classList.add("markdown");
    container.replaceChildren(...renderBlocks(String(text ?? "").replace(/\r\n?/g, "\n").split("\n")));
}

function renderBlocks(lines) {
    const nodes = [];
    let index = 0;
    while (index < lines.length) {
        const line = lines[index];
        if (!line.trim()) {
            index++;
            continue;
        }
        const fence = line.match(FENCE_RE);
        if (fence) {
            const block = readFence(lines, index, fence);
            nodes.push(block.node);
            index = block.next;
            continue;
        }
        const heading = line.match(HEADING_RE);
        if (heading) {
            const level = Math.min(heading[1].length, 4);
            const node = document.createElement(`h${level}`);
            appendInline(node, heading[2]);
            nodes.push(node);
            index++;
            continue;
        }
        if (RULE_RE.test(line)) {
            nodes.push(document.createElement("hr"));
            index++;
            continue;
        }
        if (QUOTE_RE.test(line)) {
            const inner = [];
            while (index < lines.length && QUOTE_RE.test(lines[index])) {
                inner.push(lines[index].match(QUOTE_RE)[1]);
                index++;
            }
            const quote = document.createElement("blockquote");
            quote.append(...renderBlocks(inner));
            nodes.push(quote);
            continue;
        }
        if (isTableStart(lines, index)) {
            const table = readTable(lines, index);
            nodes.push(table.node);
            index = table.next;
            continue;
        }
        if (LIST_RE.test(line)) {
            const list = readList(lines, index);
            nodes.push(list.node);
            index = list.next;
            continue;
        }
        const paragraph = [];
        while (index < lines.length && lines[index].trim() && !isBlockStart(lines, index)) {
            paragraph.push(lines[index]);
            index++;
        }
        const node = document.createElement("p");
        appendInline(node, paragraph.join("\n"));
        nodes.push(node);
    }
    return nodes;
}

function isBlockStart(lines, index) {
    const line = lines[index];
    return FENCE_RE.test(line) || HEADING_RE.test(line) || RULE_RE.test(line)
        || QUOTE_RE.test(line) || LIST_RE.test(line) || isTableStart(lines, index);
}

function readFence(lines, start, fence) {
    const marker = fence[1][0];
    const length = fence[1].length;
    const language = (fence[2] || "").trim();
    const content = [];
    let index = start + 1;
    while (index < lines.length) {
        const closing = lines[index].trim();
        if (closing.startsWith(marker.repeat(length))) {
            index++;
            break;
        }
        content.push(lines[index]);
        index++;
    }
    return {node: codeBlock(content.join("\n"), language), next: index};
}

function codeBlock(code, language) {
    const wrap = el("div", "md-code");
    const head = el("div", "md-code-head");
    head.append(el("span", "md-code-lang", language || "code"));
    const copy = el("button", "md-code-copy", "复制");
    copy.type = "button";
    copy.addEventListener("click", () => copyCode(copy, code));
    head.append(copy);
    const pre = document.createElement("pre");
    const body = document.createElement("code");
    body.textContent = code;
    pre.append(body);
    wrap.append(head, pre);
    return wrap;
}

async function copyCode(button, code) {
    try {
        if (navigator.clipboard?.writeText) {
            await navigator.clipboard.writeText(code);
        } else {
            fallbackCopy(code);
        }
        button.textContent = "已复制";
    } catch {
        button.textContent = "复制失败";
    }
    clearTimeout(button._timer);
    button._timer = setTimeout(() => {
        button.textContent = "复制";
    }, 1500);
}

function fallbackCopy(code) {
    const area = document.createElement("textarea");
    area.value = code;
    area.style.position = "fixed";
    area.style.opacity = "0";
    document.body.append(area);
    area.select();
    document.execCommand("copy");
    area.remove();
}

function isTableStart(lines, index) {
    const line = lines[index];
    const next = lines[index + 1];
    if (!next || !line.includes("|") || !next.includes("|")) {
        return false;
    }
    const header = splitRow(line);
    const separator = splitRow(next);
    return header.length >= 2 && header.length === separator.length
        && separator.every((cell) => TABLE_SEP_RE.test(cell));
}

function splitRow(line) {
    return line.trim().replace(/^\|/, "").replace(/\|$/, "")
        .split(/(?<!\\)\|/).map((cell) => cell.replace(/\\\|/g, "|").trim());
}

function readTable(lines, start) {
    const header = splitRow(lines[start]);
    const separator = splitRow(lines[start + 1]);
    const aligns = separator.map((cell) => {
        const left = cell.startsWith(":");
        const right = cell.endsWith(":");
        if (left && right) {
            return "center";
        }
        return right ? "right" : left ? "left" : "";
    });
    const wrap = el("div", "md-table-wrap");
    const table = document.createElement("table");
    const thead = document.createElement("thead");
    const headRow = document.createElement("tr");
    header.forEach((cell, index) => {
        const th = document.createElement("th");
        if (aligns[index]) {
            th.style.textAlign = aligns[index];
        }
        appendInline(th, cell);
        headRow.append(th);
    });
    thead.append(headRow);
    table.append(thead);
    const tbody = document.createElement("tbody");
    let index = start + 2;
    while (index < lines.length && lines[index].trim() && lines[index].includes("|")) {
        const row = document.createElement("tr");
        const cells = splitRow(lines[index]);
        for (let column = 0; column < header.length; column++) {
            const td = document.createElement("td");
            if (aligns[column]) {
                td.style.textAlign = aligns[column];
            }
            appendInline(td, cells[column] || "");
            row.append(td);
        }
        tbody.append(row);
        index++;
    }
    table.append(tbody);
    wrap.append(table);
    return {node: wrap, next: index};
}

function readList(lines, start) {
    const first = lines[start].match(LIST_RE);
    const base = first[1].length;
    const ordered = Boolean(first[3]);
    const list = document.createElement(ordered ? "ol" : "ul");
    if (ordered && Number(first[3]) !== 1) {
        list.start = Number(first[3]);
    }
    let lastItem = null;
    let index = start;
    while (index < lines.length) {
        const line = lines[index];
        const match = line.match(LIST_RE);
        if (!match) {
            if (line.trim() && lastItem && line.match(/^\s*/)[0].length > base) {
                lastItem.append(document.createElement("br"));
                appendInline(lastItem, line.trim());
                index++;
                continue;
            }
            break;
        }
        if (match[1].length < base) {
            break;
        }
        if (match[1].length === base) {
            if (Boolean(match[3]) !== ordered) {
                break;
            }
            lastItem = document.createElement("li");
            appendInline(lastItem, match[4]);
            list.append(lastItem);
            index++;
            continue;
        }
        const nested = readList(lines, index);
        if (lastItem) {
            lastItem.append(nested.node);
        }
        index = nested.next;
    }
    return {node: list, next: index};
}

function appendInline(parent, text) {
    let rest = String(text ?? "");
    while (rest) {
        let best = null;
        for (const rule of INLINE_RULES) {
            rule.re.lastIndex = 0;
            const match = rule.re.exec(rest);
            if (match && (!best || match.index < best.match.index)) {
                best = {rule, match};
            }
        }
        if (!best) {
            appendText(parent, rest);
            break;
        }
        if (best.match.index > 0) {
            appendText(parent, rest.slice(0, best.match.index));
        }
        parent.append(buildInline(best.rule.type, best.match));
        rest = rest.slice(best.match.index + best.match[0].length);
    }
}

function buildInline(type, match) {
    if (type === "code") {
        return el("code", "md-inline-code", match[1]);
    }
    if (type === "strong" || type === "em" || type === "del") {
        const tag = type === "strong" ? "strong" : type === "em" ? "em" : "del";
        const node = document.createElement(tag);
        appendInline(node, match[1]);
        return node;
    }
    if (type === "link" || type === "image") {
        const label = type === "image" ? (match[1] || "图片") : match[1];
        const href = safeHref(match[2]);
        if (!href) {
            return el("span", "", match[0]);
        }
        const link = el("a", "", label);
        link.href = href;
        link.target = "_blank";
        link.rel = "noopener noreferrer";
        return link;
    }
    if (type === "autolink") {
        const link = el("a", "", match[0]);
        link.href = match[0];
        link.target = "_blank";
        link.rel = "noopener noreferrer";
        return link;
    }
    return el("span", "", match[0]);
}

function safeHref(raw) {
    try {
        const url = new URL(raw, window.location.origin);
        return SAFE_PROTOCOLS.has(url.protocol) ? url.href : null;
    } catch {
        return null;
    }
}

function appendText(parent, text) {
    const parts = String(text).split("\n");
    parts.forEach((part, index) => {
        if (part) {
            parent.append(document.createTextNode(part));
        }
        if (index < parts.length - 1) {
            parent.append(document.createElement("br"));
        }
    });
}
