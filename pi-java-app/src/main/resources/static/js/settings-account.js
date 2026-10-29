/**
 * 账号与空间设置页。
 *
 * 所有登录用户都能看到自己的身份；租户与账号管理只对管理员展示，
 * 非管理员不会发出管理接口请求，避免设置页出现必然 403 的入口。
 * 空间删除是软删除：需要输入空间短名确认，删除后进入“已删除”列表，
 * 可以随时恢复，空间内的数据不会丢失。
 */

import * as api from "./api.js";
import * as auth from "./auth.js";
import { el } from "./render.js";
import { toast } from "./store.js";
import {
    actionButton, definitionList, field, pill, sectionHeader, selectInput, textInput
} from "./settings-ui.js";

const ROLE_LABELS = { ADMIN: "管理员", MEMBER: "成员" };
const STATUS_LABELS = { ACTIVE: "启用", DISABLED: "停用" };

/**
 * 渲染账号与空间。
 *
 * @param {HTMLElement} container 面板容器
 */
export async function render(container) {
    const user = auth.currentUser();
    container.replaceChildren();
    container.append(accountSection(user));
    if (user?.role !== "ADMIN") {
        const note = el("div", "settings-callout");
        note.append(el("strong", "", "需要管理员权限"));
        note.append(el("p", "", "只有管理员可以创建空间与账号。你仍然可以正常使用当前空间里的会话与工具。"));
        container.append(note);
        return;
    }
    const [tenants, users] = await Promise.all([api.listTenants(), api.listUsers()]);
    container.append(tenantSection(container, user, tenants));
    container.append(userSection(container, users));
}

function accountSection(user) {
    const section = el("section");
    section.append(sectionHeader("当前账号",
        "账号密码以 PBKDF2 哈希保存；浏览器只持有 HttpOnly 会话 Cookie。", []));
    section.append(definitionList([
        ["显示名", user?.displayName || user?.username || "—"],
        ["登录名", user?.username],
        ["角色", ROLE_LABELS[user?.role] || user?.role],
        ["所属空间", `${user?.tenantName || ""}（${user?.tenantSlug || ""}）`]
    ]));
    return section;
}

function tenantSection(container, user, tenants) {
    const section = el("section", "settings-storage");
    section.append(sectionHeader("空间",
        "每个空间拥有独立的会话、工具策略与附件；模型、插件和技能由整个进程共享。", []));
    const active = tenants.filter((tenant) => !tenant.deletedAt);
    const archived = tenants.filter((tenant) => tenant.deletedAt);
    const list = el("div", "account-list");
    for (const tenant of active) {
        list.append(tenantRow(container, list, user, tenant));
    }
    section.append(list);
    section.append(tenantForm(container));
    if (archived.length) {
        section.append(archivedBlock(container, archived));
    }
    return section;
}

function tenantRow(container, list, user, tenant) {
    const row = el("div", "account-row");
    const copy = el("div", "account-row-copy");
    copy.append(el("strong", "", tenant.name));
    copy.append(el("small", "", `${tenant.slug} · ${tenant.id}`));
    row.append(copy);
    const actions = el("div", "account-actions");
    const isDefault = tenant.slug === "default";
    const current = tenant.id === user?.tenantId;
    if (isDefault) {
        actions.append(pill("默认空间", "muted"));
    }
    if (current) {
        actions.append(pill("当前空间", "ok"));
    }
    if (!isDefault && !current) {
        actions.append(deleteButton(container, list, row, tenant));
    }
    row.append(actions);
    return row;
}

function deleteButton(container, list, row, tenant) {
    const button = actionButton("删除", () => {
        button.disabled = true;
        const panel = confirmPanel(container, row, tenant, button);
        row.after(panel);
        panel.querySelector("input").focus();
    }, "danger");
    button.classList.add("is-mini");
    return button;
}

function confirmPanel(container, row, tenant, trigger) {
    const panel = el("div", "account-confirm");
    const copy = el("div", "account-confirm-copy");
    copy.append(el("strong", "", `删除空间「${tenant.name}」？`));
    copy.append(el("small", "",
        "空间会从列表和登录页消失，成员无法再登录；会话与文件会保留，可随时恢复。"));
    const input = textInput("", `输入 ${tenant.slug} 确认`);
    const actions = el("div", "account-confirm-actions");
    const cancel = actionButton("取消", () => {
        panel.remove();
        trigger.disabled = false;
    }, "ghost");
    const confirm = actionButton("确认删除", () => archive(container, tenant, input, confirm), "danger");
    confirm.disabled = true;
    input.addEventListener("input", () => {
        input.classList.remove("is-invalid");
        confirm.disabled = input.value.trim() !== tenant.slug;
    });
    actions.append(cancel, confirm);
    panel.append(copy, input, actions);
    return panel;
}

async function archive(container, tenant, input, button) {
    if (input.value.trim() !== tenant.slug) {
        input.classList.add("is-invalid");
        input.focus();
        return;
    }
    button.disabled = true;
    try {
        await api.deleteTenant(tenant.id);
        toast(`已删除空间「${tenant.name}」，可在下方恢复`);
        await render(container);
    } catch (failure) {
        button.disabled = false;
        toast(failure.message || "删除空间失败", true);
    }
}

function archivedBlock(container, archived) {
    const block = el("div", "account-archive");
    block.append(el("p", "settings-form-title", "已删除的空间"));
    block.append(el("p", "settings-hint",
        "这些空间的成员暂时无法登录，数据仍然保留。恢复后成员可以正常登录。"));
    const list = el("div", "account-list");
    for (const tenant of archived) {
        list.append(archivedRow(container, tenant));
    }
    block.append(list);
    return block;
}

function archivedRow(container, tenant) {
    const row = el("div", "account-row account-row-archived");
    const copy = el("div", "account-row-copy");
    copy.append(el("strong", "", tenant.name));
    copy.append(el("small", "", `${tenant.slug} · 删除于 ${formatDate(tenant.deletedAt)}`));
    row.append(copy);
    const actions = el("div", "account-actions");
    actions.append(pill("已删除", "danger"));
    const restoreButton = actionButton("恢复", () => restore(container, tenant), "primary");
    restoreButton.classList.add("is-mini");
    actions.append(restoreButton);
    row.append(actions);
    return row;
}

async function restore(container, tenant) {
    try {
        await api.restoreTenant(tenant.id);
        toast(`已恢复空间「${tenant.name}」`);
        await render(container);
    } catch (failure) {
        toast(failure.message || "恢复空间失败", true);
    }
}

function formatDate(value) {
    if (!value) {
        return "—";
    }
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleDateString("zh-CN");
}

function tenantForm(container) {
    const form = el("form", "settings-form");
    form.append(el("p", "settings-form-title", "创建空间"));
    const slug = textInput("", "例如 design");
    const name = textInput("", "例如 设计组");
    const username = textInput("", "管理员登录名");
    const displayName = textInput("", "管理员显示名，可选");
    const password = passwordInput("至少 8 位");
    const error = el("p", "settings-form-error hidden");
    const grid = el("div", "settings-form-grid");
    grid.append(
        field("空间短名", slug, "小写字母、数字与连字符，2-32 位"),
        field("空间名称", name),
        field("管理员账号", username),
        field("管理员显示名", displayName),
        field("初始密码", password, "至少 8 位，交给该空间管理员后请尽快修改"));
    form.append(grid, error);
    const actions = el("div", "settings-form-actions");
    const submit = actionButton("创建空间", () => {}, "primary");
    submit.type = "submit";
    actions.append(submit);
    form.append(actions);
    form.addEventListener("submit", async (event) => {
        event.preventDefault();
        error.classList.add("hidden");
        try {
            const tenant = await api.createTenant({
                slug: slug.value.trim(),
                name: name.value.trim(),
                username: username.value.trim(),
                displayName: displayName.value.trim(),
                password: password.value
            });
            toast(`已创建空间「${tenant.name}」`);
            await render(container);
        } catch (failure) {
            error.textContent = failure.message || "创建空间失败";
            error.classList.remove("hidden");
        }
    });
    return form;
}

function userSection(container, users) {
    const section = el("section", "settings-storage");
    section.append(sectionHeader("本空间账号",
        "账号只能访问所属空间的数据；管理员可以在这里添加成员。", []));
    if (!users.length) {
        section.append(el("p", "settings-hint", "还没有其他账号。"));
    } else {
        const list = el("div", "account-list");
        for (const user of users) {
            const row = el("div", "account-row");
            const copy = el("div", "account-row-copy");
            copy.append(el("strong", "", user.displayName || user.username));
            copy.append(el("small", "", user.username));
            row.append(copy);
            row.append(pill(ROLE_LABELS[user.role] || user.role,
                user.role === "ADMIN" ? "warn" : "muted"));
            row.append(pill(STATUS_LABELS[user.status] || user.status || "—",
                user.status === "ACTIVE" ? "ok" : "danger"));
            list.append(row);
        }
        section.append(list);
    }
    section.append(userForm(container));
    return section;
}

function userForm(container) {
    const form = el("form", "settings-form");
    form.append(el("p", "settings-form-title", "添加账号"));
    const username = textInput("", "登录名");
    const displayName = textInput("", "显示名，可选");
    const role = selectInput([
        { label: "成员", value: "MEMBER" },
        { label: "管理员", value: "ADMIN" }
    ], "MEMBER");
    const password = passwordInput("至少 8 位");
    const error = el("p", "settings-form-error hidden");
    const grid = el("div", "settings-form-grid");
    grid.append(
        field("登录名", username),
        field("显示名", displayName),
        field("角色", role),
        field("初始密码", password)
    );
    form.append(grid, error);
    const actions = el("div", "settings-form-actions");
    const submit = actionButton("添加账号", () => {}, "primary");
    submit.type = "submit";
    actions.append(submit);
    form.append(actions);
    form.addEventListener("submit", async (event) => {
        event.preventDefault();
        error.classList.add("hidden");
        try {
            const created = await api.createUser({
                username: username.value.trim(),
                displayName: displayName.value.trim(),
                password: password.value,
                role: role.value
            });
            toast(`已添加账号 ${created.username}`);
            await render(container);
        } catch (failure) {
            error.textContent = failure.message || "添加账号失败";
            error.classList.remove("hidden");
        }
    });
    return form;
}

function passwordInput(placeholder) {
    const input = document.createElement("input");
    input.type = "password";
    input.autocomplete = "new-password";
    input.minLength = 8;
    input.maxLength = 128;
    input.placeholder = placeholder || "";
    return input;
}
