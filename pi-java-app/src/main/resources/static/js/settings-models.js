/**
 * 模型与密钥设置页。
 *
 * 表单只提交 provider、URL、模型名与“环境变量名”；密钥值永远不会离开
 * 用户的环境变量。启停与保存成功后同步 app 级状态，让新建会话对话框立即
 * 看到最新档案。
 */

import * as api from "./api.js";
import { el, formatNumber } from "./render.js";
import { updateHeader } from "./session-view.js";
import { state, toast } from "./store.js";
import {
    actionButton, checkbox, codeBlock, copyButton, definitionList, emptyState, field,
    numberInput, pill, providerLabel, sectionHeader, selectInput, textInput
} from "./settings-ui.js";

const PROVIDERS = [
    { value: "FAKE", label: "Fake（离线演示）" },
    { value: "OPENAI_COMPATIBLE", label: "OpenAI-compatible" },
    { value: "ANTHROPIC", label: "Anthropic" }
];

const DEFAULTS = {
    contextWindow: 128000,
    maxOutputTokens: 8192,
    baseUrl: "https://api.openai.com/v1",
    apiKeyEnv: "OPENAI_API_KEY"
};

let containerRef = null;
let models = [];
let editing = null;

/**
 * 渲染模型设置页。
 *
 * @param {HTMLElement} container 面板容器
 */
export async function render(container) {
    containerRef = container;
    models = await api.listModels();
    container.replaceChildren();
    container.append(sectionHeader("模型档案",
        "每个会话绑定一个档案；密钥只以环境变量名保存，服务端不接触明文。", [
            actionButton("新建档案", () => openEditor(null), "primary")
        ]));
    container.append(keyGuide());
    if (editing) {
        container.append(editor());
    }
    if (!models.length) {
        container.append(emptyState("还没有模型档案",
            "点击“新建档案”添加一个端点。离线体验可以直接选择 Fake。"));
        return;
    }
    const list = el("div", "model-list");
    for (const model of models) {
        list.append(modelCard(model));
    }
    container.append(list);
}

function modelCard(model) {
    const endpoint = model.endpoint || {};
    const card = el("article", "model-card" + (model.enabled ? "" : " is-disabled"));
    const head = el("div", "model-card-head");
    head.append(el("span", "provider-mark", providerLabel(endpoint.provider)));
    const copy = el("div", "model-card-title");
    copy.append(el("strong", "", model.displayName || model.id));
    copy.append(el("small", "", `${model.id} · ${endpoint.modelName || "—"}`));
    head.append(copy);
    head.append(pill(model.enabled ? "已启用" : "已停用", model.enabled ? "ok" : "muted"));
    card.append(head);

    card.append(definitionList([
        ["Base URL", endpoint.baseUrl || "（离线，无需 URL）"],
        ["上下文窗口", `${formatNumber(endpoint.contextWindow)} tokens`],
        ["最大输出", `${formatNumber(endpoint.maxOutputTokens)} tokens`],
        ["能力", capabilityText(endpoint.capabilities)]
    ]));

    const foot = el("div", "model-card-foot");
    const key = keyStatus(model);
    foot.append(key);
    if (model.version > 1) {
        foot.append(pill(`v${model.version}`, "muted"));
    }
    const actions = el("div", "model-card-actions");
    actions.append(actionButton("编辑", () => openEditor(model), "mini"));
    actions.append(actionButton(model.enabled ? "停用" : "启用",
        () => toggleModel(model), model.enabled ? "danger" : "mini"));
    foot.append(actions);
    card.append(foot);
    return card;
}

function keyStatus(model) {
    if (model.endpoint?.provider === "FAKE") {
        return pill("无需密钥", "ok");
    }
    if (model.apiKeyConfigured) {
        return pill(`${model.endpoint?.apiKeyEnv} 已配置`, "ok");
    }
    return pill(`${model.endpoint?.apiKeyEnv} 未配置`, "warn");
}

function capabilityText(capabilities = {}) {
    const parts = [
        capabilities.streaming ? "流式" : null,
        capabilities.toolCalls ? "工具" : null,
        capabilities.thinking ? "思考" : null
    ].filter(Boolean);
    return parts.length ? parts.join(" · ") : "未声明";
}

function keyGuide() {
    const box = el("div", "settings-callout");
    box.append(el("strong", "", "密钥如何配置"));
    box.append(el("p", "", "这里只保存变量名。设置环境变量后重启服务；任何密钥都不会写入数据库或返回给浏览器。"));
    const commands = el("div", "key-commands");
    const powershell = el("div", "key-command");
    powershell.append(el("span", "", "PowerShell"));
    powershell.append(codeBlock('$env:OPENAI_API_KEY = "你的密钥"'), copyButton('$env:OPENAI_API_KEY = "你的密钥"'));
    const bash = el("div", "key-command");
    bash.append(el("span", "", "Bash / macOS / Linux"));
    bash.append(codeBlock('export OPENAI_API_KEY="你的密钥"'), copyButton('export OPENAI_API_KEY="你的密钥"'));
    commands.append(powershell, bash);
    box.append(commands);
    box.append(el("small", "", "FAKE 档案不需要密钥；OpenAI-compatible 与 Anthropic 档案才会读取对应环境变量。"));
    return box;
}

function openEditor(model) {
    editing = model ? {
        id: model.id,
        displayName: model.displayName,
        endpoint: { ...model.endpoint, capabilities: { ...model.endpoint?.capabilities } }
    } : {
        id: "",
        displayName: "",
        endpoint: {
            provider: "OPENAI_COMPATIBLE",
            baseUrl: DEFAULTS.baseUrl,
            apiKeyEnv: DEFAULTS.apiKeyEnv,
            modelName: "",
            contextWindow: DEFAULTS.contextWindow,
            maxOutputTokens: DEFAULTS.maxOutputTokens,
            capabilities: { streaming: true, toolCalls: true, thinking: false }
        }
    };
    render(containerRef);
}

function editor() {
    const endpoint = editing.endpoint;
    const form = el("form", "settings-form model-editor");
    const idInput = textInput(editing.id, "例如 deepseek-chat");
    idInput.disabled = Boolean(editing.id);
    const displayName = textInput(editing.displayName, "例如 DeepSeek V3");
    const provider = selectInput(PROVIDERS, endpoint.provider || "OPENAI_COMPATIBLE");
    const baseUrl = textInput(endpoint.baseUrl, "https://api.deepseek.com/v1");
    const apiKeyEnv = textInput(endpoint.apiKeyEnv, "DEEPSEEK_API_KEY");
    const modelName = textInput(endpoint.modelName, "发给 provider 的模型名");
    const contextWindow = numberInput(endpoint.contextWindow, 1024);
    const maxOutputTokens = numberInput(endpoint.maxOutputTokens, 1);
    const capabilities = endpoint.capabilities || {};
    const streaming = checkbox("流式输出", capabilities.streaming);
    const toolCalls = checkbox("工具调用", capabilities.toolCalls);
    const thinking = checkbox("思考输出", capabilities.thinking);
    const error = el("p", "settings-form-error hidden");

    const grid = el("div", "settings-form-grid");
    grid.append(field("档案 ID", idInput, editing.id ? "ID 创建后不可修改" : "小写字母、数字与中划线"),
        field("展示名", displayName));
    grid.append(field("Provider", provider));
    grid.append(field("模型名", modelName));
    grid.append(field("Base URL", baseUrl, "OpenAI-compatible 需要带版本路径"));
    grid.append(field("密钥环境变量名", apiKeyEnv, "只填变量名，例如 DEEPSEEK_API_KEY"));
    grid.append(field("上下文窗口", contextWindow, "tokens"));
    grid.append(field("最大输出", maxOutputTokens, "不能超过上下文窗口"));
    const checks = el("div", "settings-checks");
    checks.append(streaming.wrapper, toolCalls.wrapper, thinking.wrapper);
    form.append(grid, checks, error);

    const syncProvider = () => {
        const fake = provider.value === "FAKE";
        baseUrl.disabled = fake;
        apiKeyEnv.disabled = fake;
        if (fake) {
            baseUrl.value = "";
            apiKeyEnv.value = "";
        } else if (!baseUrl.value) {
            baseUrl.value = DEFAULTS.baseUrl;
        }
        if (!fake && !apiKeyEnv.value) {
            apiKeyEnv.value = DEFAULTS.apiKeyEnv;
        }
    };
    provider.addEventListener("change", syncProvider);
    syncProvider();

    const footer = el("div", "settings-form-actions");
    footer.append(actionButton("取消", () => {
        editing = null;
        render(containerRef);
    }));
    const submit = actionButton(editing.id ? "保存修改" : "创建档案", () => {}, "primary");
    submit.type = "submit";
    footer.append(submit);
    form.append(footer);

    form.addEventListener("submit", async (event) => {
        event.preventDefault();
        error.classList.add("hidden");
        const payload = buildPayload();
        if (typeof payload === "string") {
            error.textContent = payload;
            error.classList.remove("hidden");
            return;
        }
        submit.disabled = true;
        try {
            if (editing.id) {
                await api.updateModel(editing.id, payload);
            } else {
                await api.createModel({ id: idInput.value.trim(), ...payload });
            }
            toast(editing.id ? "模型档案已更新" : "模型档案已创建");
            editing = null;
            await refreshModels();
            await render(containerRef);
        } catch (requestError) {
            error.textContent = requestError.message || "保存失败";
            error.classList.remove("hidden");
        } finally {
            submit.disabled = false;
        }
    });
    return form;

    function buildPayload() {
        const selectedProvider = provider.value;
        const fake = selectedProvider === "FAKE";
        const id = idInput.value.trim();
        const name = displayName.value.trim();
        const model = modelName.value.trim();
        const context = Number(contextWindow.value);
        const output = Number(maxOutputTokens.value);
        if (!id && !editing.id) {
            return "档案 ID 不能为空";
        }
        if (!name) {
            return "展示名不能为空";
        }
        if (!model) {
            return "模型名不能为空";
        }
        if (!Number.isFinite(context) || context < 1024) {
            return "上下文窗口至少为 1024";
        }
        if (!Number.isFinite(output) || output < 1 || output > context) {
            return "最大输出必须在 1 与上下文窗口之间";
        }
        if (!fake && (!baseUrl.value.trim() || !apiKeyEnv.value.trim())) {
            return "非 Fake provider 必须填写 Base URL 与密钥环境变量名";
        }
        return {
            displayName: name,
            endpoint: {
                provider: selectedProvider,
                baseUrl: fake ? "" : baseUrl.value.trim(),
                apiKeyEnv: fake ? "" : apiKeyEnv.value.trim().toUpperCase(),
                modelName: model,
                contextWindow: context,
                maxOutputTokens: output,
                capabilities: {
                    streaming: streaming.input.checked,
                    toolCalls: toolCalls.input.checked,
                    thinking: thinking.input.checked
                }
            }
        };
    }
}

async function toggleModel(model) {
    try {
        await api.setModelEnabled(model.id, !model.enabled);
        toast(`${model.displayName} 已${model.enabled ? "停用" : "启用"}`);
        await refreshModels();
        await render(containerRef);
    } catch (error) {
        toast(error.message || "更新模型状态失败", true);
    }
}

async function refreshModels() {
    state.models = await api.listModels();
    updateHeader();
}
