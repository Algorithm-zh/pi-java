/**
 * 工具参数表单。
 *
 * 从工具 JSON Schema 生成普通表单：字符串、数字、开关、下拉与多行文本，
 * 用户填写后自动拼出 `/tool <name> <json>`，不需要手写 JSON。表单只负责
 * 收集与校验参数，真正的运行仍走消息管线（含审批与策略）。
 */

import { el } from "./dom.js";
import { fieldHelp, fieldLabel, riskLabel } from "./tools.js";

const MULTILINE_KEYS = new Set([
    "content", "command", "oldText", "newText", "task", "persona", "text"
]);

let host = null;
let backdrop = null;
let card = null;
let onRun = () => {};
let currentTool = null;
let readers = [];
let errorNode = null;

/**
 * 初始化表单宿主。
 *
 * @param {object} options 配置
 * @param {Function} options.onRun 收到 `/tool ...` 文本后的运行回调
 */
export function init(options = {}) {
    onRun = options.onRun || onRun;
    ensureHost();
    document.addEventListener("keydown", (event) => {
        if (event.key === "Escape" && host && !host.classList.contains("hidden")) {
            close();
        }
    });
}

/**
 * 打开指定工具的参数表单。
 *
 * @param {object} tool 工具条目
 */
export function open(tool) {
    if (!tool) {
        return;
    }
    ensureHost();
    currentTool = tool;
    host.replaceChildren();
    backdrop = el("div", "tool-modal-backdrop");
    card = el("form", "tool-modal-card");
    card.setAttribute("role", "dialog");
    card.setAttribute("aria-modal", "true");
    card.setAttribute("aria-labelledby", "tool-modal-title");
    backdrop.addEventListener("click", close);
    card.addEventListener("submit", (event) => {
        event.preventDefault();
        submit();
    });

    card.append(header(tool));
    card.append(metaRow(tool));
    const fields = el("div", "tool-fields");
    readers = buildFields(fields, tool);
    card.append(fields);
    if (!readers.length) {
        card.append(el("p", "tool-form-note", "这个工具不需要填写参数，直接运行即可。"));
    }
    const preview = el("pre", "tool-json-preview");
    const details = document.createElement("details");
    details.className = "tool-json-details";
    const summary = document.createElement("summary");
    summary.textContent = "查看参数 JSON（高级）";
    details.append(summary, preview);
    card.append(details);

    errorNode = el("p", "tool-form-error hidden");
    card.append(errorNode);
    card.append(footer());

    const refresh = () => {
        try {
            preview.textContent = JSON.stringify(collect(readers), null, 2);
            errorNode.classList.add("hidden");
        } catch (invalid) {
            preview.textContent = "（参数还不完整）";
        }
    };
    fields.addEventListener("input", refresh);
    fields.addEventListener("change", refresh);
    refresh();

    host.append(backdrop, card);
    host.classList.remove("hidden");
    document.body.classList.add("tool-modal-open");
    const first = fields.querySelector("input, textarea, select");
    if (first) {
        first.focus();
    } else {
        card.querySelector(".tool-run").focus();
    }
    if (tool.requiresApproval) {
        card.querySelector(".tool-run").title = "运行前会弹出审批";
    }
}

/**
 * 关闭表单。
 */
export function close() {
    if (host) {
        host.classList.add("hidden");
        host.replaceChildren();
    }
    document.body.classList.remove("tool-modal-open");
    currentTool = null;
}

function ensureHost() {
    if (host) {
        return;
    }
    host = el("div", "tool-modal hidden");
    document.body.append(host);
}

function header(tool) {
    const head = el("header", "tool-modal-head");
    head.append(el("span", "tool-modal-mark", "TOOL"));
    const copy = el("div", "tool-modal-copy");
    const title = el("h2", "tool-modal-title", tool.displayName);
    title.id = "tool-modal-title";
    copy.append(title);
    if (tool.description) {
        copy.append(el("p", "tool-modal-desc", tool.description));
    }
    const closeButton = el("button", "tool-modal-close", "×");
    closeButton.type = "button";
    closeButton.setAttribute("aria-label", "关闭");
    closeButton.addEventListener("click", close);
    head.append(copy, closeButton);
    return head;
}

function metaRow(tool) {
    const meta = el("div", "tool-modal-meta");
    if (tool.group) {
        meta.append(el("span", "meta-pill", tool.group));
    }
    meta.append(el("span", "meta-pill", riskLabel(tool.risk)));
    if (tool.requiresApproval) {
        meta.append(el("span", "meta-pill is-warn", "运行前需要审批"));
    }
    const name = el("code", "tool-modal-id", tool.name);
    name.title = tool.name;
    meta.append(name);
    return meta;
}

function buildFields(container, tool) {
    const properties = tool.schema && tool.schema.properties
        ? tool.schema.properties
        : null;
    if (!properties) {
        return [rawJsonField(container)];
    }
    const required = new Set(Array.isArray(tool.schema.required) ? tool.schema.required : []);
    const readers = [];
    for (const [key, property] of Object.entries(properties)) {
        readers.push(field(container, key, property || {}, required.has(key)));
    }
    return readers;
}

function field(container, key, property, required) {
    const row = el("div", "tool-field");
    const label = el("label", "tool-field-label");
    const inputId = `tool-field-${key.replace(/[^a-zA-Z0-9_-]/g, "-")}`;
    label.setAttribute("for", inputId);
    label.append(el("span", "", fieldLabel(key, property)));
    if (required) {
        label.append(el("span", "tool-required", "*"));
    }
    const help = fieldHelp(key, property);
    if (help) {
        label.append(el("small", "tool-field-help", help));
    }
    row.append(label);
    const control = controlFor(key, property, inputId, required);
    row.append(control.node);
    container.append(row);
    return {
        key,
        property,
        required,
        read: control.read
    };
}

function controlFor(key, property, inputId, required) {
    const type = property.type;
    if (type === "boolean") {
        const wrap = el("label", "tool-switch");
        const input = document.createElement("input");
        input.type = "checkbox";
        input.id = inputId;
        input.checked = Boolean(property.default);
        wrap.append(input, el("span", "", "是"));
        return {
            node: wrap,
            read: () => ({present: true, value: input.checked})
        };
    }
    if (Array.isArray(property.enum) && property.enum.length) {
        const select = el("select", "tool-input");
        select.id = inputId;
        select.append(option("", required ? "请选择…" : "不设置", ""));
        for (const value of property.enum) {
            const item = option(String(value), String(value), String(value));
            if (property.default === value) {
                item.selected = true;
            }
            select.append(item);
        }
        return {
            node: select,
            read: () => textValue(select.value, required, `${fieldLabel(key, property)}不能为空`)
        };
    }
    if (type === "integer" || type === "number") {
        const input = numberInput(inputId, property);
        return {
            node: input,
            read: () => numberValue(input, property, required, fieldLabel(key, property))
        };
    }
    if (type === "array" || type === "object") {
        const textarea = el("textarea", "tool-input tool-textarea");
        textarea.id = inputId;
        textarea.rows = 3;
        textarea.placeholder = type === "array" ? '["第一项", "第二项"]' : '{"键": "值"}';
        return {
            node: textarea,
            read: () => jsonValue(textarea, required, fieldLabel(key, property))
        };
    }
    const multiline = MULTILINE_KEYS.has(key)
        || property.format === "textarea"
        || (property.maxLength && property.maxLength > 500);
    const input = document.createElement(multiline ? "textarea" : "input");
    input.className = multiline ? "tool-input tool-textarea" : "tool-input";
    input.id = inputId;
    if (multiline) {
        input.rows = key === "content" || key === "command" ? 4 : 3;
    } else {
        input.type = "text";
    }
    if (property.maxLength) {
        input.maxLength = property.maxLength;
    }
    if (typeof property.default === "string") {
        input.value = property.default;
    }
    const label = fieldLabel(key, property);
    input.placeholder = required ? `请输入${label}` : `可选，${label}`;
    return {
        node: input,
        read: () => textValue(input.value, required, `${label}不能为空`)
    };
}

function numberInput(inputId, property) {
    const input = document.createElement("input");
    input.className = "tool-input";
    input.id = inputId;
    input.type = "number";
    if (property.type === "integer") {
        input.step = "1";
    } else {
        input.step = "any";
    }
    if (property.minimum !== undefined) {
        input.min = String(property.minimum);
    }
    if (property.maximum !== undefined) {
        input.max = String(property.maximum);
    }
    if (property.default !== undefined) {
        input.value = String(property.default);
    }
    return input;
}

function option(value, text, label) {
    const item = document.createElement("option");
    item.value = value;
    item.textContent = text;
    if (label !== undefined) {
        item.label = label;
    }
    return item;
}

function textValue(value, required, message) {
    const text = String(value ?? "");
    if (!text.trim()) {
        return required ? {error: message} : {present: false};
    }
    return {present: true, value: text};
}

function numberValue(input, property, required, label) {
    const raw = input.value.trim();
    if (!raw) {
        return required ? {error: `${label}不能为空`} : {present: false};
    }
    const value = Number(raw);
    if (!Number.isFinite(value)) {
        return {error: `${label}必须是数字`};
    }
    if (property.type === "integer" && !Number.isInteger(value)) {
        return {error: `${label}必须是整数`};
    }
    if (property.minimum !== undefined && value < Number(property.minimum)) {
        return {error: `${label}不能小于 ${property.minimum}`};
    }
    if (property.maximum !== undefined && value > Number(property.maximum)) {
        return {error: `${label}不能大于 ${property.maximum}`};
    }
    return {present: true, value};
}

function jsonValue(textarea, required, label) {
    const raw = textarea.value.trim();
    if (!raw) {
        return required ? {error: `${label}不能为空`} : {present: false};
    }
    try {
        return {present: true, value: JSON.parse(raw)};
    } catch {
        return {error: `${label}不是合法的 JSON`};
    }
}

function rawJsonField(container) {
    const row = el("div", "tool-field");
    const label = el("label", "tool-field-label", "参数 JSON");
    const textarea = el("textarea", "tool-input tool-textarea");
    textarea.id = "tool-field-raw-json";
    textarea.rows = 5;
    textarea.placeholder = '{"key": "value"}';
    row.append(label, textarea);
    container.append(row);
    return {
        key: "__raw__",
        required: false,
        read: () => {
            const raw = textarea.value.trim();
            if (!raw) {
                return {present: false};
            }
            try {
                const value = JSON.parse(raw);
                if (!value || typeof value !== "object" || Array.isArray(value)) {
                    return {error: "参数必须是 JSON 对象"};
                }
                return {present: true, value, raw: true};
            } catch {
                return {error: "参数不是合法的 JSON"};
            }
        }
    };
}

function footer() {
    const foot = el("footer", "tool-modal-foot");
    const hint = el("span", "tool-modal-hint", "填写后运行；需要审批的工具会先弹窗确认。");
    const actions = el("div", "tool-modal-actions");
    const cancel = el("button", "tool-cancel", "取消");
    cancel.type = "button";
    cancel.addEventListener("click", close);
    const run = el("button", "tool-run", "运行");
    run.type = "submit";
    actions.append(cancel, run);
    foot.append(hint, actions);
    return foot;
}

function collect(readers) {
    const args = {};
    for (const reader of readers) {
        const result = reader.read();
        if (result.error) {
            throw new Error(result.error);
        }
        if (!result.present) {
            continue;
        }
        if (result.raw) {
            Object.assign(args, result.value);
        } else {
            args[reader.key] = result.value;
        }
    }
    return args;
}

function submit() {
    if (!currentTool || !card) {
        return;
    }
    let args;
    try {
        args = collect(readers);
    } catch (invalid) {
        errorNode.textContent = invalid.message;
        errorNode.classList.remove("hidden");
        return;
    }
    const command = `/tool ${currentTool.name} ${JSON.stringify(args)}`;
    const tool = currentTool;
    close();
    onRun(command, tool);
}
