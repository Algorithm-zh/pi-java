/**
 * 输入框斜杠命令提示。
 *
 * 只在输入以命令 token 开头时出现；支持过滤、方向键、回车/Tab 选择与 Esc 关闭。
 * 技能列表按需从服务端加载，正文不会在此处读取。
 */

import * as api from "./api.js";
import { el } from "./dom.js";
import { dom } from "./store.js";

const COMMANDS = [
    {name: "/read", insert: "/read ", args: "<路径>", group: "工具指令", desc: "读取工作区文件"},
    {name: "/ls", insert: "/ls .", args: "[目录]", group: "工具指令", desc: "列出目录内容"},
    {
        name: "/write", insert: "/write ", args: "<路径> <内容>", group: "工具指令",
        desc: "创建或覆盖文件（需要审批）"
    },
    {
        name: "/tool", insert: "/tool ", args: "<工具名> <JSON>", group: "工具指令",
        desc: "调用已注册的工具或插件"
    }
];

let skills = null;
let skillsLoading = false;
let options = [];
let active = -1;
let open = false;
let onResize = () => {};

export function init(optionsHandler = {}) {
    onResize = optionsHandler.onResize || onResize;
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
    if (query === null) {
        hide();
        return;
    }
    if (!skills && !skillsLoading) {
        loadSkills();
    }
    const matches = matchCommands(query);
    if (!matches.length) {
        hide();
        return;
    }
    open = true;
    active = 0;
    render(matches, query);
}

function currentQuery() {
    const value = dom.input.value;
    if (!value.startsWith("/") || /\s/.test(value)) {
        return null;
    }
    return value.toLowerCase();
}

function matchCommands(query) {
    const list = [...COMMANDS];
    if (skills) {
        for (const skill of skills) {
            list.push({
                name: `/skill:${skill.name}`,
                insert: `/skill:${skill.name} `,
                args: "<任务>",
                group: "技能",
                desc: skill.description || "注入技能正文",
                key: `${skill.name} ${skill.description || ""}`.toLowerCase()
            });
        }
    }
    if (query === "/") {
        return list;
    }
    return list.filter((item) => `${item.name} ${item.key || ""}`.toLowerCase()
        .startsWith(query));
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

function render(items, query) {
    dom.slashMenu.replaceChildren();
    options = [];
    let group = "";
    for (const item of items) {
        if (item.group !== group) {
            group = item.group;
            dom.slashMenu.append(el("div", "slash-group", group));
        }
        const button = el("button", "slash-item");
        button.type = "button";
        button.id = `slash-option-${options.length}`;
        button.dataset.index = String(options.length);
        button.setAttribute("role", "option");
        button.append(
            el("span", "slash-command", [item.name, item.args].filter(Boolean).join(" ")),
            el("span", "slash-desc", item.desc)
        );
        options.push({button, item});
        dom.slashMenu.append(button);
    }
    if (query.startsWith("/skill:") && skills && !skills.length) {
        dom.slashMenu.append(el("div", "slash-empty", "未发现技能，可在设置中心查看技能目录"));
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
    dom.input.value = option.item.insert;
    onResize();
    hide();
    dom.input.focus();
    const end = dom.input.value.length;
    dom.input.setSelectionRange(end, end);
}
