/**
 * 会话设置对话框：重命名、切换模型与思考级别、归档。
 *
 * 只有空闲会话允许修改；归档后整个表单只读，避免用户误以为还能继续
 * 发送消息。保存后同步侧边栏、头部与上下文仪表。
 */

import * as api from "./api.js";
import * as contextMeter from "./context-meter.js";
import { statusLabel } from "./render.js";
import { refreshSessions, setComposerEnabled, updateHeader } from "./session-view.js";
import { dom, state, toast } from "./store.js";

let dialog;
let form;
let titleInput;
let modelSelect;
let thinkingSelect;
let meta;
let error;
let saveButton;
let archiveButton;
let availableModels = [];

/**
 * 绑定静态 DOM。必须在页面加载后调用一次。
 */
export function init() {
    dialog = document.getElementById("session-settings-dialog");
    form = document.getElementById("session-settings-form");
    titleInput = document.getElementById("session-settings-title");
    modelSelect = document.getElementById("session-settings-model");
    thinkingSelect = document.getElementById("session-settings-thinking");
    meta = document.getElementById("session-settings-meta");
    error = document.getElementById("session-settings-error");
    saveButton = document.getElementById("session-settings-save");
    archiveButton = document.getElementById("session-archive");
    dom.sessionSettingsOpen.addEventListener("click", () => open());
    document.getElementById("session-settings-close")
        .addEventListener("click", () => dialog.close());
    document.getElementById("session-settings-cancel")
        .addEventListener("click", () => dialog.close());
    form.addEventListener("submit", save);
    archiveButton.addEventListener("click", archive);
    modelSelect.addEventListener("change", () => {
        error.classList.add("hidden");
        if (syncThinking(true)) {
            error.textContent = "当前模型不支持思考输出，已将思考级别重置为关闭。";
            error.classList.remove("hidden");
        }
    });
}

/**
 * 打开当前会话的设置。
 */
export async function open() {
    if (!state.session) {
        toast("请先选择会话", true);
        return;
    }
    const sessionId = state.session.id;
    try {
        const [session, models] = await Promise.all([
            api.getSession(sessionId),
            api.listModels()
        ]);
        if (state.session?.id !== sessionId) {
            return;
        }
        state.session = session;
        state.models = models;
        availableModels = models;
        updateHeader();
        fill(session, models);
        if (!dialog.open) {
            dialog.showModal();
        }
    } catch (requestError) {
        toast(requestError.message || "加载会话设置失败", true);
    }
}

function fill(session, models) {
    const editable = session.status === "IDLE";
    titleInput.value = session.title;
    titleInput.disabled = !editable;
    modelSelect.replaceChildren();
    const options = models.filter((model) =>
        model.enabled || model.id === session.modelProfileId);
    if (!options.some((model) => model.id === session.modelProfileId)) {
        options.unshift({
            id: session.modelProfileId,
            displayName: session.modelProfileId,
            enabled: false
        });
    }
    for (const model of options) {
        const label = `${model.displayName || model.id}${model.enabled ? "" : "（已停用）"}`;
        modelSelect.append(new Option(label, model.id));
    }
    modelSelect.value = session.modelProfileId;
    modelSelect.disabled = !editable;
    thinkingSelect.value = session.thinkingLevel || "OFF";
    thinkingSelect.disabled = !editable;
    archiveButton.disabled = !editable;
    archiveButton.textContent = session.status === "ARCHIVED" ? "已归档" : "归档会话";
    saveButton.disabled = !editable;
    error.classList.add("hidden");
    if (syncThinking(editable)) {
        error.textContent = "当前模型不支持思考输出，已将思考级别重置为关闭；保存后生效。";
        error.classList.remove("hidden");
    }
    meta.textContent = [
        session.id,
        statusLabel(session.status),
        session.cwd,
        editable ? "" : "当前状态不可修改"
    ].filter(Boolean).join(" / ");
}

function syncThinking(allowReset) {
    const model = availableModels.find((item) => item.id === modelSelect.value);
    const supportsThinking = Boolean(model?.endpoint?.capabilities?.thinking);
    for (const option of thinkingSelect.options) {
        option.disabled = option.value !== "OFF" && !supportsThinking;
    }
    if (!supportsThinking && allowReset && thinkingSelect.value !== "OFF") {
        thinkingSelect.value = "OFF";
        return true;
    }
    return false;
}

async function save(event) {
    event.preventDefault();
    if (!state.session || state.session.status !== "IDLE") {
        return;
    }
    const session = state.session;
    const title = titleInput.value.trim();
    if (!title) {
        showError("标题不能为空");
        return;
    }
    const modelId = modelSelect.value;
    const thinking = thinkingSelect.value;
    const titleChanged = title !== session.title;
    const modelChanged = modelId !== session.modelProfileId
        || thinking !== (session.thinkingLevel || "OFF");
    if (!titleChanged && !modelChanged) {
        dialog.close();
        return;
    }
    saveButton.disabled = true;
    try {
        let updated = session;
        if (titleChanged) {
            updated = await api.renameSession(session.id, title);
        }
        if (modelChanged) {
            updated = await api.changeSessionModel(session.id, modelId, thinking);
        }
        state.session = updated;
        await refreshSessions();
        updateHeader();
        setComposerEnabled(true);
        contextMeter.refresh();
        dialog.close();
        toast("会话设置已保存");
    } catch (requestError) {
        showError(requestError.message || "保存失败");
    } finally {
        saveButton.disabled = state.session?.status !== "IDLE";
    }
}

async function archive() {
    if (!state.session || state.session.status !== "IDLE") {
        return;
    }
    const session = state.session;
    const confirmed = window.confirm(
        `归档「${session.title}」？归档后会话只读，仍可在列表中查看历史。`);
    if (!confirmed) {
        return;
    }
    archiveButton.disabled = true;
    try {
        state.session = await api.archiveSession(session.id);
        await refreshSessions();
        updateHeader();
        setComposerEnabled(false);
        contextMeter.refresh();
        dialog.close();
        toast("会话已归档");
    } catch (requestError) {
        showError(requestError.message || "归档失败");
        archiveButton.disabled = false;
    }
}

function showError(message) {
    error.textContent = message;
    error.classList.remove("hidden");
}
