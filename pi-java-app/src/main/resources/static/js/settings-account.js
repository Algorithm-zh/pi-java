/**
 * 账号与空间设置页。
 *
 * 所有登录用户都能看到自己的身份；租户与账号管理只对管理员展示，
 * 非管理员不会发出管理接口请求，避免设置页出现必然 403 的入口。
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
    const list = el("div", "account-list");
    for (const tenant of tenants) {
        const row = el("div", "account-row");
        const copy = el("div", "account-row-copy");
        copy.append(el("strong", "", tenant.name));
        copy.append(el("small", "", `${tenant.slug} · ${tenant.id}`));
        row.append(copy);
        const current = tenant.id === user?.tenantId;
        row.append(pill(current ? "当前空间" : "空间", current ? "ok" : "muted"));
        list.append(row);
    }
    section.append(list);
    section.append(tenantForm(container));
    return section;
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
