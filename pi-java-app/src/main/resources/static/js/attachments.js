/**
 * 对话框图片附件。
 *
 * 图片先上传到服务端，发送消息时只带附件 ID；消息历史通过
 * {@code /api/v1/attachments/{id}} 直接引用原图。这里负责选择、粘贴、
 * 拖放、上传进度与移除，不参与会话状态机。
 */

import * as api from "./api.js";
import { el } from "./dom.js";
import { dom, state, toast } from "./store.js";

const MAX_IMAGES = 4;
const MAX_BYTES = 5 * 1024 * 1024;
const MAX_SOURCE_BYTES = 20 * 1024 * 1024;
const MAX_EDGE = 1568;
const COMPRESS_THRESHOLD = 1.5 * 1024 * 1024;
const SUPPORTED_TYPES = new Set(["image/png", "image/jpeg", "image/webp", "image/gif"]);
const SUPPORTED_EXT = /\.(png|jpe?g|webp|gif)$/i;

let pending = [];
let onChange = null;
let dragDepth = 0;

/**
 * 绑定输入框、按钮与拖放事件。
 *
 * @param {{onChange?: () => void}} options 状态变化回调
 */
export function init(options = {}) {
    onChange = options.onChange || null;
    if (!dom.attachButton || !dom.composerFile || !dom.composerAttachments) {
        return;
    }
    dom.attachButton.addEventListener("click", chooseFiles);
    dom.composerFile.addEventListener("change", () => {
        const files = [...dom.composerFile.files];
        dom.composerFile.value = "";
        addFiles(files);
    });
    dom.input.addEventListener("paste", onPaste);
    const form = dom.composerForm;
    form.addEventListener("dragenter", onDragEnter);
    form.addEventListener("dragover", onDragOver);
    form.addEventListener("dragleave", onDragLeave);
    form.addEventListener("drop", onDrop);
    render();
}

/**
 * 已上传完成、可以随消息发送的图片。
 *
 * @returns {Array<{id: string, name: string, url: string, mediaType: string}>} 图片
 */
export function ready() {
    return pending
        .filter((item) => item.status === "ready")
        .map(({ id, name, url, mediaType }) => ({ id, name, url, mediaType }));
}

/**
 * 是否还有图片在上传。
 *
 * @returns {boolean} 有上传任务时返回 true
 */
export function isUploading() {
    return pending.some((item) => item.status === "uploading");
}

/**
 * 是否有可发送的图片。
 *
 * @returns {boolean} 有已上传图片时返回 true
 */
export function hasReady() {
    return pending.some((item) => item.status === "ready");
}

/**
 * 清空待发送图片。
 */
export function clear() {
    for (const item of pending) {
        revoke(item);
    }
    pending = [];
    render();
}

function chooseFiles() {
    if (!state.session) {
        toast("先创建或选择一个会话，再添加图片", true);
        return;
    }
    if (!modelSupportsVision()) {
        toast("当前模型未声明图片输入能力，请在设置里改用支持视觉的模型", true);
        return;
    }
    dom.composerFile.click();
}

function modelSupportsVision() {
    const id = state.session?.modelProfileId;
    const model = state.models.find((item) => item.id === id);
    return model ? Boolean(model.endpoint?.capabilities?.vision) : true;
}

function addFiles(fileList) {
    const files = [...(fileList || [])];
    const images = files.filter(isImage);
    if (!images.length) {
        toast("仅支持 PNG、JPEG、WebP 或 GIF 图片", true);
        return;
    }
    if (!state.session || !modelSupportsVision()) {
        toast("当前模型不支持图片输入，请先切换模型", true);
        return;
    }
    const room = MAX_IMAGES - pending.length;
    if (room <= 0) {
        toast(`一条消息最多 ${MAX_IMAGES} 张图片`, true);
        return;
    }
    if (images.length > room) {
        toast(`一条消息最多 ${MAX_IMAGES} 张图片，已保留前 ${room} 张`);
    }
    for (const file of images.slice(0, room)) {
        if (file.size > MAX_SOURCE_BYTES) {
            toast(`「${file.name || "图片"}」超过 20 MB`, true);
            continue;
        }
        const item = {
            key: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
            name: file.name || "image",
            size: file.size,
            originalSize: file.size,
            optimized: false,
            url: "",
            localUrl: URL.createObjectURL(file),
            status: "uploading"
        };
        pending.push(item);
        render();
        upload(item, file);
    }
}

async function upload(item, file) {
    try {
        const prepared = await prepare(file);
        if (prepared !== file) {
            item.size = prepared.size;
            item.optimized = true;
            if (item.localUrl) {
                URL.revokeObjectURL(item.localUrl);
            }
            item.localUrl = URL.createObjectURL(prepared);
            render();
        }
        if (prepared.size > MAX_BYTES) {
            throw new Error("图片压缩后仍超过 5 MB，请换一张更小的图片");
        }
        const metadata = await api.uploadAttachment(prepared);
        item.id = metadata.id;
        item.url = metadata.url;
        if (!item.optimized) {
            item.name = metadata.name || item.name;
        }
        item.mediaType = metadata.mediaType;
        item.size = metadata.size || item.size;
        item.status = "ready";
        revoke(item);
    } catch (error) {
        item.status = "error";
        toast(error.message || "图片上传失败", true);
    }
    render();
}

/**
 * 上传前缩放并转码，减少请求体积与模型 token 消耗。
 *
 * GIF 保留原样以维持动画；浏览器解码失败、Canvas 不可用或压缩后反而
 * 更大时也回退原文件，交给服务端继续处理。
 *
 * @param {File} file 原始文件
 * @returns {Promise<File>} 可直接上传的文件
 */
async function prepare(file) {
    if (file.type === "image/gif" || typeof createImageBitmap !== "function") {
        return file;
    }
    let bitmap;
    try {
        bitmap = await createImageBitmap(file, { imageOrientation: "from-image" });
    } catch {
        return file;
    }
    try {
        const longest = Math.max(bitmap.width, bitmap.height);
        if (longest <= MAX_EDGE && file.size <= COMPRESS_THRESHOLD) {
            return file;
        }
        const scale = Math.min(1, MAX_EDGE / longest);
        const width = Math.max(1, Math.round(bitmap.width * scale));
        const height = Math.max(1, Math.round(bitmap.height * scale));
        const canvas = document.createElement("canvas");
        canvas.width = width;
        canvas.height = height;
        const context = canvas.getContext("2d");
        if (!context) {
            return file;
        }
        context.drawImage(bitmap, 0, 0, width, height);
        const blob = await toBlob(canvas);
        if (!blob || blob.size >= file.size) {
            return file;
        }
        return new File([blob], rename(file.name, blob.type), {
            type: blob.type,
            lastModified: Date.now()
        });
    } catch {
        return file;
    } finally {
        bitmap.close?.();
    }
}

function toBlob(canvas) {
    return new Promise((resolve) => canvas.toBlob(resolve, "image/webp", 0.85));
}

function rename(name, mediaType) {
    const extension = mediaType === "image/webp" ? "webp"
        : mediaType === "image/png" ? "png" : "jpg";
    const base = (name || "image").replace(/\.[^./\\]+$/, "") || "image";
    return `${base}.${extension}`;
}

function removeItem(target) {
    const index = pending.indexOf(target);
    if (index < 0) {
        return;
    }
    revoke(target);
    pending.splice(index, 1);
    render();
}

function revoke(item) {
    if (item.localUrl) {
        URL.revokeObjectURL(item.localUrl);
        item.localUrl = "";
    }
}

function render() {
    const box = dom.composerAttachments;
    if (!box) {
        return;
    }
    box.replaceChildren();
    box.classList.toggle("hidden", pending.length === 0);
    for (const item of pending) {
        box.append(renderChip(item));
    }
    onChange?.();
}

function renderChip(item) {
    const chip = el("div", "attachment-chip"
        + (item.status === "error" ? " is-error" : "")
        + (item.status === "uploading" ? " is-uploading" : ""));
    if (item.optimized && item.status === "ready") {
        chip.title = `原图 ${formatSize(item.originalSize)}，已压缩为 ${formatSize(item.size)}`;
    }
    const thumb = document.createElement("img");
    thumb.src = item.localUrl || item.url;
    thumb.alt = item.name || "图片";
    chip.append(thumb);

    const meta = el("div", "attachment-meta");
    meta.append(el("span", "attachment-name", item.name || "图片"),
        el("span", "attachment-state", stateLabel(item)));
    chip.append(meta);

    const remove = el("button", "attachment-remove", "×");
    remove.type = "button";
    remove.title = "移除图片";
    remove.setAttribute("aria-label", `移除图片 ${item.name || ""}`.trim());
    remove.addEventListener("click", () => removeItem(item));
    chip.append(remove);
    return chip;
}

function stateLabel(item) {
    if (item.status === "uploading") {
        return "处理中…";
    }
    if (item.status === "error") {
        return "上传失败";
    }
    const size = formatSize(item.size);
    return item.optimized ? `${size} · 已优化` : size;
}

function formatSize(bytes) {
    if (!Number.isFinite(bytes) || bytes <= 0) {
        return "";
    }
    if (bytes >= 1024 * 1024) {
        return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
    }
    return `${Math.max(1, Math.round(bytes / 1024))} KB`;
}

function isImage(file) {
    const type = (file.type || "").toLowerCase();
    if (SUPPORTED_TYPES.has(type)) {
        return true;
    }
    return !type && SUPPORTED_EXT.test(file.name || "");
}

function onPaste(event) {
    const items = event.clipboardData?.items;
    if (!items) {
        return;
    }
    const files = [];
    for (const item of items) {
        if (item.kind === "file" && item.type.startsWith("image/")) {
            const file = item.getAsFile();
            if (file) {
                files.push(file);
            }
        }
    }
    if (!files.length) {
        return;
    }
    event.preventDefault();
    addFiles(files);
}

function hasFiles(event) {
    return [...(event.dataTransfer?.types || [])].includes("Files");
}

function onDragEnter(event) {
    if (!hasFiles(event)) {
        return;
    }
    event.preventDefault();
    dragDepth += 1;
    dom.composerForm.classList.add("is-dragging");
}

function onDragOver(event) {
    if (!hasFiles(event)) {
        return;
    }
    event.preventDefault();
    event.dataTransfer.dropEffect = "copy";
}

function onDragLeave(event) {
    if (!hasFiles(event)) {
        return;
    }
    dragDepth = Math.max(0, dragDepth - 1);
    if (dragDepth === 0) {
        dom.composerForm.classList.remove("is-dragging");
    }
}

function onDrop(event) {
    if (!hasFiles(event)) {
        return;
    }
    event.preventDefault();
    dragDepth = 0;
    dom.composerForm.classList.remove("is-dragging");
    addFiles(event.dataTransfer.files);
}
