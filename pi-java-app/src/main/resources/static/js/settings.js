/**
 * 设置中心外壳：标签页切换、加载态与错误态。
 *
 * 各标签页由独立模块渲染，本文件不包含任何业务字段，避免设置面板再次
 * 变成一个巨量脚本。
 */

import * as models from "./settings-models.js";
import * as skills from "./settings-skills.js";
import * as plugins from "./settings-plugins.js";
import * as policies from "./settings-policies.js";
import * as system from "./settings-system.js";
import { errorState, loadingState } from "./settings-ui.js";

const LOADERS = {
    models: models.render,
    skills: skills.render,
    plugins: plugins.render,
    policies: policies.render,
    system: system.render
};

const TAB_LABELS = {
    models: "模型与密钥",
    skills: "技能",
    plugins: "插件",
    policies: "工具策略",
    system: "系统信息"
};

let dialog;
let tabs;
let panel;
let activeTab = "models";
let loadToken = 0;

/**
 * 绑定静态 DOM 事件。必须在页面加载后调用一次。
 */
export function init() {
    dialog = document.getElementById("settings-dialog");
    tabs = document.getElementById("settings-tabs");
    panel = document.getElementById("settings-panel");
    document.getElementById("settings-close")
        .addEventListener("click", () => dialog.close());
    tabs.addEventListener("click", (event) => {
        const button = event.target.closest("[data-tab]");
        if (button) {
            show(button.dataset.tab);
        }
    });
}

/**
 * 打开设置中心。
 *
 * @param {string} tab 初始标签页
 */
export function open(tab = "models") {
    if (!dialog.open) {
        dialog.showModal();
    }
    show(tab);
}

/**
 * 供其他模块在配置变更后跳转到指定标签。
 *
 * @param {string} tab 标签名
 */
export function refresh(tab = activeTab) {
    if (dialog?.open) {
        show(tab);
    }
}

async function show(tab) {
    const name = LOADERS[tab] ? tab : "models";
    activeTab = name;
    updateTabs();
    const token = ++loadToken;
    panel.replaceChildren(loadingState(`正在加载${TAB_LABELS[name]}…`));
    try {
        await LOADERS[name](panel);
    } catch (error) {
        if (token === loadToken) {
            panel.replaceChildren(errorState(error.message || "请求失败", () => show(name)));
        }
    }
}

function updateTabs() {
    for (const button of tabs.querySelectorAll("[data-tab]")) {
        const selected = button.dataset.tab === activeTab;
        button.classList.toggle("active", selected);
        button.setAttribute("aria-selected", String(selected));
    }
}
