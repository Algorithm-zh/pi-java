/**
 * 设置中心的共享 DOM 构件。
 *
 * 面板模块只负责数据与行为，这里的函数统一标题、空态、按钮与代码块的
 * 结构与样式，避免各标签页视觉不一致。
 */

import { el } from "./render.js";
import { toast } from "./store.js";

export function sectionHeader(title, description, actions = []) {
    const header = el("div", "settings-section-head");
    const copy = el("div", "settings-section-copy");
    copy.append(el("h3", "", title));
    if (description) {
        copy.append(el("p", "", description));
    }
    header.append(copy);
    if (actions.length) {
        const group = el("div", "settings-actions");
        actions.forEach((action) => group.append(action));
        header.append(group);
    }
    return header;
}

export function actionButton(label, onClick, variant = "ghost") {
    const button = el("button", `settings-button is-${variant}`, label);
    button.type = "button";
    button.addEventListener("click", onClick);
    return button;
}

export function pill(text, tone = "muted") {
    return el("span", `settings-pill is-${tone}`, text);
}

export function emptyState(title, description) {
    const box = el("div", "settings-empty");
    box.append(el("strong", "", title), el("p", "", description));
    return box;
}

export function loadingState(text = "加载中…") {
    return el("div", "settings-loading", text);
}

export function errorState(message, retry) {
    const box = el("div", "settings-error");
    box.append(el("strong", "", "加载失败"), el("p", "", message), actionButton("重试", retry));
    return box;
}

export function definitionList(entries) {
    const list = el("dl", "settings-meta");
    for (const [label, value] of entries) {
        const row = el("div");
        row.append(el("dt", "", label), el("dd", "", value || "—"));
        list.append(row);
    }
    return list;
}

export function field(labelText, control, hint) {
    const wrapper = el("label", "settings-field");
    wrapper.append(el("span", "settings-field-label", labelText));
    wrapper.append(control);
    if (hint) {
        wrapper.append(el("small", "settings-field-hint", hint));
    }
    return wrapper;
}

export function textInput(value = "", placeholder = "") {
    const input = document.createElement("input");
    input.type = "text";
    input.value = value || "";
    input.placeholder = placeholder;
    return input;
}

export function numberInput(value, min = 1) {
    const input = document.createElement("input");
    input.type = "number";
    input.min = String(min);
    input.value = value === undefined || value === null ? "" : String(value);
    return input;
}

export function selectInput(options, value) {
    const select = document.createElement("select");
    for (const option of options) {
        const node = new Option(option.label, option.value);
        select.append(node);
    }
    if (value !== undefined && value !== null) {
        select.value = value;
    }
    return select;
}

export function checkbox(labelText, checked) {
    const wrapper = el("label", "settings-check");
    const input = document.createElement("input");
    input.type = "checkbox";
    input.checked = Boolean(checked);
    wrapper.append(input, el("span", "", labelText));
    return { wrapper, input };
}

export function codeBlock(text) {
    const block = el("pre", "settings-code");
    block.textContent = text;
    return block;
}

export function copyButton(text, label = "复制") {
    return actionButton(label, () => copyText(text), "mini");
}

export async function copyText(text) {
    try {
        await navigator.clipboard.writeText(text);
        toast("已复制到剪贴板");
    } catch {
        toast("复制失败，请手动选择文本", true);
    }
}

export function formatInstant(value) {
    if (!value) {
        return "";
    }
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString("zh-CN");
}

export function providerLabel(provider) {
    return {
        FAKE: "Fake",
        OPENAI_COMPATIBLE: "OpenAI 兼容",
        ANTHROPIC: "Anthropic"
    }[provider] || provider || "";
}
