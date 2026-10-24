/**
 * 新建会话对话框。
 *
 * 表单只负责收集参数，真正的创建与跳转由 app.js 注入的回调完成；
 * 这样对话框不需要理解会话 API 与页面状态。
 */

const dom = {
    dialog: document.getElementById("session-dialog"),
    form: document.getElementById("session-form"),
    title: document.getElementById("field-title"),
    cwd: document.getElementById("field-cwd"),
    model: document.getElementById("field-model"),
    thinking: document.getElementById("field-thinking"),
    manageModels: document.getElementById("dialog-manage-models"),
    error: document.getElementById("dialog-error"),
    close: document.getElementById("dialog-close"),
    cancel: document.getElementById("dialog-cancel"),
    submit: document.getElementById("dialog-submit")
};

let createHandler = null;
let manageHandler = null;
let dismissHandler = null;
let availableModels = [];

/**
 * 绑定创建回调并注册表单事件。
 *
 * @param {(payload: object) => Promise<void>} handler 创建会话回调
 * @param {() => void} onManageModels 打开模型管理回调
 * @param {() => void} onDismiss 关闭对话框回调，用于丢弃未发送的草稿
 */
export function init(handler, onManageModels, onDismiss) {
    createHandler = handler;
    manageHandler = onManageModels;
    dismissHandler = onDismiss;
    dom.form.addEventListener("submit", submit);
    dom.close.addEventListener("click", () => dom.dialog.close());
    dom.cancel.addEventListener("click", () => dom.dialog.close());
    dom.dialog.addEventListener("close", () => dismissHandler?.());
    dom.model.addEventListener("change", () => {
        dom.error.classList.add("hidden");
        syncThinking();
    });
    dom.manageModels.addEventListener("click", () => {
        dom.dialog.close();
        manageHandler?.();
    });
}

/**
 * 打开对话框并重置为默认值。
 *
 * @param {Array<object>} models 模型档案
 * @param {object} system 系统信息
 */
export function open(models, system) {
    dom.error.classList.add("hidden");
    dom.title.value = "新会话";
    dom.cwd.value = system?.workspaceRoot || "";
    dom.thinking.value = "OFF";
    dom.model.replaceChildren();
    const enabled = (models || []).filter((model) => model.enabled);
    availableModels = enabled;
    if (!enabled.length) {
        dom.model.append(new Option("没有启用的模型档案", ""));
        dom.error.textContent = "请先创建或启用一个模型档案（demo profile 会自动创建 fake-demo）。";
        dom.error.classList.remove("hidden");
        dom.submit.disabled = true;
    } else {
        for (const model of enabled) {
            dom.model.append(new Option(`${model.displayName}（${model.id}）`, model.id));
        }
        dom.model.value = enabled.find((model) => model.id === "fake-demo")?.id || enabled[0].id;
        dom.submit.disabled = false;
        syncThinking();
    }
    dom.dialog.showModal();
    dom.title.select();
}

function syncThinking() {
    const model = availableModels.find((item) => item.id === dom.model.value);
    const supportsThinking = Boolean(model?.endpoint?.capabilities?.thinking);
    for (const option of dom.thinking.options) {
        option.disabled = option.value !== "OFF" && !supportsThinking;
    }
    if (!supportsThinking) {
        dom.thinking.value = "OFF";
    }
}

async function submit(event) {
    event.preventDefault();
    const title = dom.title.value.trim();
    const cwd = dom.cwd.value.trim();
    const modelProfileId = dom.model.value;
    const thinkingLevel = dom.thinking.value;
    if (!title || !cwd || !modelProfileId) {
        showError("标题、工作目录与模型都不能为空");
        return;
    }
    dom.submit.disabled = true;
    try {
        await createHandler({ title, cwd, modelProfileId, thinkingLevel });
        dom.dialog.close();
    } catch (error) {
        showError(error.message || "创建会话失败");
    } finally {
        dom.submit.disabled = false;
    }
}

function showError(message) {
    dom.error.textContent = message;
    dom.error.classList.remove("hidden");
}
