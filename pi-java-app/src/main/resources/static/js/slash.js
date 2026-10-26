/**
 * 输入框斜杠命令提示。
 *
 * 只在输入以命令 token 开头时出现；支持过滤、方向键、回车/Tab 选择与 Esc 关闭。
 * 输入 `/tool ` 后切换为工具目录：直接列出所有工具的中文名称与说明，选中后
 * 交给参数表单，用户不必手写 JSON。
 */

import * as api from "./api.js";
import { el } from "./dom.js";
import { dom } from "./store.js";
import * as tools from "./tools.js";

const COMMANDS = [
    {name: "/read", insert: "/read ", args: "<路径>", group: "工具指令", desc: "读取工作区文件"},
    {name: "/ls", insert: "/ls .", args: "[目录]", group: "工具指令", desc: "列出目录内容"},
    {
        name: "/write", insert: "/write ", args: "<路径> <内容>", group: "工具指令",
        desc: "创建或覆盖文件（需要审批）"
    },
    {
        name: "/tool", insert: "/tool ", args: "", group: "工具指令",
        desc: "选择工具并填写参数，无需手写 JSON"
    }
];

let skills = null;
let skillsLoading = false;
let options = [];
let active = -1;
let open = false;
let toolsLoading = false;
let onResize = () => {};
let onToolChosen = () => {};

export function init(optionsHandler = {}) {
    onResize = optionsHandler.onResize || onResize;
    onToolChosen = optionsHandler.onToolChosen || onToolChosen;
    dom.input.setAttribute("aria-autocomplete", "list");
    dom.input.setAttribute("aria-controls", "slash-menu");
    dom.input.setAttribute("aria-expanded", "false");
    dom.input.addEventListener("input", () => {
        update();
    });
    dom.input.addEventListener("blur", () => {
        setTimeout(hide, 120);
    });
    document.addEventListener("pointerdown", (event) => {
        if (open && !dom.slashMenu.contains(event.target) && event.target !== dom.input) {
            hide();
        }
    });
    dom.slashMenu.addEventListener("pointerdown", (event) => {
        const item = event.target.closest("[data-index]");
        if (!item) {
            return;
        }
        event.preventDefault();
        choose(Number(item.dataset.index));
    });
}

/**
 * 处理输入框按键；菜单打开时拦截导航与确认键。
 *
 * @param {KeyboardEvent} event 键盘事件
 * @returns {boolean} 是否已处理
 */
export function handleKeydown(event) {
    if (!open) {
        return false;
    }
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
        event.preventDefault();
        move(event.key === "ArrowDown" ? 1 : -1);
        return true;
    }
    if (event.key === "Enter" || event.key === "Tab") {
        if (!event.isComposing) {
            event.preventDefault();
            choose(active);
            return true;
        }
    }
    if (event.key === "Escape") {
        event.preventDefault();
        hide();
        return true;
    }
    return false;
}

export function hide() {
    if (!open) {
        return;
    }
    open = false;
    options = [];
    active = -1;
    dom.slashMenu.replaceChildren();
    dom.slashMenu.classList.add("hidden");
    dom.input.setAttribute("aria-expanded", "false");
    dom.input.removeAttribute("aria-activedescendant");
}

function update() {
    const query = currentQuery();
    if (!query) {
        hide();
        return;
    }
    if (query.mode === "tools") {
        updateTools(query.filter);
        return;
    }
    if (!skills && !skillsLoading) {
        loadSkills();
    }
    const matches = matchCommands(query.filter);
    if (!matches.length) {
        hide();
        return;
    }
    open = true;
    active = 0;
    render(matches);
}

function currentQuery() {
    const value = dom.input.value;
    if (!value.startsWith("/")) {
        return null;
    }
    const toolMatch = /^\/tool(?:\s+([\s\S]*))?$/i.exec(value);
    if (toolMatch) {
        return {mode: "tools", filter: (toolMatch[1] || "").trim().toLowerCase()};
    }
    if (/\s/.test(value)) {
        return null;
    }
    return {mode: "commands", filter: value.toLowerCase()};
}

function updateTools(filter) {
    if (!tools.isLoaded()) {
        open = true;
        active = -1;
        renderMessage("正在加载工具…");
        loadTools();
        return;
    }
    const matches = tools.current().filter((tool) => !filter || tool.search.includes(filter));
    if (!matches.length) {
        hide();
        return;
    }
    open = true;
    active = 0;
    render(matches.map((tool) => ({
        kind: "tool",
        tool,
        group: tool.group,
        primary: tool.displayName,
        secondary: tool.description,
        badge: tool.requiresApproval ? "需审批" : ""
    })));
}

async function loadTools() {
    if (toolsLoading) {
        return;
    }
    toolsLoading = true;
    try {
        await tools.load(true);
    } finally {
        toolsLoading = false;
        if (open && dom.input.value.startsWith("/tool")) {
            update();
        }
    }
}

function matchCommands(filter) {
    const list = [...COMMANDS];
    if (skills) {
        for (const skill of skills) {
            list.push({
                kind: "command",
                name: `/skill:${skill.name}`,
                insert: `/skill:${skill.name} `,
                args: "<任务>",
                group: "技能",
                desc: skill.description || "注入技能正文",
                key: `${skill.name} ${skill.description || ""}`.toLowerCase()
            });
        }
    }
    const commands = [];
    for (const item of list) {
        const entry = {kind: "command", ...item};
        const text = `${entry.name} ${entry.key || ""}`.toLowerCase();
        if (filter === "/" || text.startsWith(filter)) {
            entry.primary = [entry.name, entry.args].filter(Boolean).join(" ");
            entry.secondary = entry.desc;
            commands.push(entry);
        }
    }
    return commands;
}

async function loadSkills() {
    skillsLoading = true;
    try {
        skills = await api.listSkills();
    } catch {
        skills = [];
    } finally {
        skillsLoading = false;
        if (dom.input.value.startsWith("/")) {
            update();
        }
    }
}

function renderMessage(text) {
    dom.slashMenu.replaceChildren(el("div", "slash-empty", text));
    dom.slashMenu.classList.remove("hidden");
    dom.input.setAttribute("aria-expanded", "true");
}

function render(items) {
    dom.slashMenu.replaceChildren();
    options = [];
    let group = "";
    for (const item of items) {
        if (item.group !== group) {
            group = item.group;
            dom.slashMenu.append(el("div", "slash-group", group));
        }
        const button = el("button", item.kind === "tool" ? "slash-item is-tool" : "slash-item");
        button.type = "button";
        button.id = `slash-option-${options.length}`;
        button.dataset.index = String(options.length);
        button.setAttribute("role", "option");
        if (item.kind === "tool") {
            button.title = item.tool.name;
        }
        button.append(
            el("span", "slash-command", item.primary),
            el("span", "slash-desc", item.secondary || "")
        );
        if (item.badge) {
            button.append(el("span", "slash-badge", item.badge));
        }
        options.push({button, item});
        dom.slashMenu.append(button);
    }
    active = Math.min(active, options.length - 1);
    dom.slashMenu.classList.remove("hidden");
    dom.input.setAttribute("aria-expanded", "true");
    highlight();
}

function move(delta) {
    if (!options.length) {
        return;
    }
    active = (active + delta + options.length) % options.length;
    highlight();
}

function highlight() {
    options.forEach((option, index) => {
        option.button.classList.toggle("active", index === active);
    });
    const current = options[active];
    if (current) {
        dom.input.setAttribute("aria-activedescendant", current.button.id);
        current.button.scrollIntoView({block: "nearest"});
    }
}

function choose(index) {
    const option = options[index];
    if (!option) {
        return;
    }
    if (option.item.kind === "tool") {
        dom.input.value = "";
        onResize();
        hide();
        dom.input.focus();
        onToolChosen(option.item.tool);
        return;
    }
    const insert = option.item.insert;
    dom.input.value = insert;
    onResize();
    dom.input.focus();
    const end = dom.input.value.length;
    dom.input.setSelectionRange(end, end);
    if (insert === "/tool ") {
        update();
    } else {
        hide();
    }
}
