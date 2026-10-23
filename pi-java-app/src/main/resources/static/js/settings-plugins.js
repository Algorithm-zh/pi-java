/**
 * 插件设置页。
 *
 * 安装只做 jar 校验与登记，显式启用才会加载代码；页面把这两个动作分开
 * 呈现，避免用户误以为“安装即执行”。加载失败的插件可以再次启用重试。
 */

import * as api from "./api.js";
import { el } from "./render.js";
import { toast } from "./store.js";
import {
    actionButton, codeBlock, emptyState, field, formatInstant, pill, sectionHeader, textInput
} from "./settings-ui.js";

const STATUS = {
    ACTIVE: { label: "运行中", tone: "ok" },
    INSTALLED: { label: "已安装 · 未启用", tone: "warn" },
    DISABLED: { label: "已停用", tone: "muted" },
    FAILED: { label: "加载失败", tone: "danger" }
};

let containerRef = null;

/**
 * 渲染插件管理页。
 *
 * @param {HTMLElement} container 面板容器
 */
export async function render(container) {
    containerRef = container;
    const plugins = await api.listPlugins();
    container.replaceChildren();
    container.append(sectionHeader("插件",
        "插件扩展工具与运行时行为。安装不会执行代码，启用才会加载进当前进程。", [
            actionButton("刷新", () => render(container))
        ]));
    container.append(guide());
    container.append(installer());
    if (!plugins.length) {
        container.append(emptyState("还没有安装插件",
            "先打包 examples/pi-plugin-example，再把生成的 jar 绝对路径粘贴到上方。"));
        return;
    }
    const list = el("div", "plugin-list");
    for (const plugin of plugins) {
        list.append(pluginCard(plugin));
    }
    container.append(list);
}

function guide() {
    const box = el("div", "settings-callout");
    box.append(el("strong", "", "插件生命周期"));
    box.append(el("p", "",
        "安装后状态为 INSTALLED；点击“启用”才会加载。停用会从工具目录中移除，"
        + "但安装记录保留。加载失败时 lastError 会显示在卡片上。"));
    box.append(codeBlock("examples/pi-plugin-example/target/*.jar"));
    box.append(el("small", "", "路径必须是服务进程可读的本地绝对路径。"));
    return box;
}

function installer() {
    const form = el("form", "settings-form plugin-install");
    const path = textInput("", "D:\\code\\pi-java\\examples\\pi-plugin-example\\target\\....jar");
    const error = el("p", "settings-form-error hidden");
    const submit = actionButton("安装插件", () => {}, "primary");
    submit.type = "submit";
    const row = el("div", "settings-inline-row");
    row.append(field("jar 路径", path), submit);
    form.append(row, error);
    form.addEventListener("submit", async (event) => {
        event.preventDefault();
        error.classList.add("hidden");
        const jarPath = path.value.trim();
        if (!jarPath) {
            error.textContent = "请填写 jar 路径";
            error.classList.remove("hidden");
            return;
        }
        submit.disabled = true;
        try {
            const installed = await api.installPlugin(jarPath);
            toast(`插件 ${installed.id} 已安装，启用后生效`);
            await render(containerRef);
        } catch (requestError) {
            error.textContent = requestError.message || "安装失败";
            error.classList.remove("hidden");
            submit.disabled = false;
        }
    });
    return form;
}

function pluginCard(plugin) {
    const status = STATUS[plugin.status] || { label: plugin.status, tone: "muted" };
    const card = el("article", "plugin-card" + (plugin.status === "FAILED" ? " is-failed" : ""));
    const head = el("div", "plugin-card-head");
    head.append(el("span", "plugin-mark", "PL"));
    const copy = el("div", "plugin-card-title");
    copy.append(el("strong", "", plugin.id));
    copy.append(el("small", "", `${plugin.version} · API ${plugin.apiVersion}`));
    head.append(copy, pill(status.label, status.tone));
    card.append(head);

    const meta = el("dl", "settings-meta");
    meta.append(metaRow("jar 路径", plugin.jarPath));
    meta.append(metaRow("更新时间", formatInstant(plugin.updatedAt) || "—"));
    card.append(meta);
    if (plugin.lastError) {
        const failure = el("div", "settings-callout is-danger");
        failure.append(el("strong", "", "最近一次错误"), el("p", "", plugin.lastError));
        card.append(failure);
    }

    const foot = el("div", "plugin-card-foot");
    const busy = el("span", "settings-hint", "");
    foot.append(busy);
    const actions = el("div", "model-card-actions");
    if (plugin.status === "ACTIVE") {
        actions.append(actionButton("停用", () => toggle(plugin, false, busy), "danger"));
    } else {
        const label = plugin.status === "FAILED" ? "重试启用" : "启用";
        actions.append(actionButton(label, () => toggle(plugin, true, busy), "primary"));
    }
    foot.append(actions);
    card.append(foot);
    return card;
}

function metaRow(label, value) {
    const row = el("div");
    row.append(el("dt", "", label), el("dd", "", value || "—"));
    return row;
}

async function toggle(plugin, enabled, busy) {
    busy.textContent = enabled ? "正在加载…" : "正在停用…";
    try {
        await api.setPluginEnabled(plugin.id, enabled);
        toast(`${plugin.id} 已${enabled ? "启用" : "停用"}`);
        await render(containerRef);
    } catch (error) {
        busy.textContent = "";
        toast(error.message || "更新插件状态失败", true);
    }
}
