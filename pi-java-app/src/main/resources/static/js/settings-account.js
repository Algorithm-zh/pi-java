/**
 * 账号与空间设置页。
 *
 * 账号是全局的，空间是账号加入的一个个独立数据仓库：注册后自动拥有个人空间，
 * 任何人还可以创建团队空间并成为管理员，或把已有账号邀请进来。
 * 这里按空间列出“我加入了哪些空间”，可直接切换；只有团队空间的管理员
 * 才能删除（软删除，可恢复）空间，默认空间、个人空间与当前空间始终保留。
 */

import * as api from "./api.js";
import * as auth from "./auth.js";
import { el } from "./render.js";
import { toast } from "./store.js";
import {
    actionButton, definitionList, field, pill, sectionHeader, selectInput, textInput
} from "./settings-ui.js";

const ROLE_LABELS = { ADMIN: "管理员", MEMBER: "成员" };
const KIND_LABELS = { DEFAULT: "默认空间", PERSONAL: "个人空间", TEAM: "团队空间" };
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
    const tenants = await api.listTenants();
    container.append(spacesSection(container, user, tenants));
    if (user?.role === "ADMIN") {
        const members = await api.listUsers();
        container.append(memberSection(container, user, members));
    } else {
        const note = el("div", "settings-callout");
        note.append(el("strong", "", "你在当前空间是成员"));
        note.append(el("p", "",
            "空间管理员可以邀请更多成员加入。你随时可以创建自己的团队空间，并成为它的管理员。"));
        container.append(note);
    }
}

function accountSection(user) {
    const section = el("section");
    section.append(sectionHeader("当前账号",
        "账号密码以 PBKDF2 哈希保存；浏览器只持有 HttpOnly 会话 Cookie。", []));
    section.append(definitionList([
        ["显示名", user?.displayName || user?.username || "—"],
        ["登录名", user?.username],
        ["当前空间", currentSpaceLabel(user)]
    ]));
    return section;
}

/*
 * 默认空间的名称与类型标签相同，重复显示会显得啰嗦；只在两者不同时补类型。
 */
function currentSpaceLabel(user) {
    const name = user?.tenantName || "—";
    const kind = spaceKindLabel(user?.tenantKind);
    const role = roleLabel(user?.role);
    return `${name}（${kind && kind !== name ? `${kind} · ${role}` : role}）`;
}

function spacesSection(container, user, tenants) {
    const section = el("section", "settings-storage");
    section.append(sectionHeader("我的空间",
        "每个空间拥有独立的会话、工具策略与附件；切换空间后看到的是该空间自己的数据。", []));
    const active = tenants.filter((tenant) => !tenant.deletedAt);
    const archived = tenants.filter((tenant) => tenant.deletedAt);
    const list = el("div", "account-list");
    for (const tenant of active) {
        list.append(tenantRow(container, list, user, tenant));
    }
    section.append(list);
    if (!active.length) {
        section.append(el("p", "settings-hint", "还没有可用的空间。"));
    }
    section.append(tenantForm(container));
    if (archived.length) {
        section.append(archivedBlock(container, archived));
    }
    return section;
}

function tenantRow(container, list, user, tenant) {
    const row = el("div", "account-row");
    row.dataset.current = String(isCurrent(user, tenant));
    const copy = el("div", "account-row-copy");
    copy.append(el("strong", "", tenant.name));
    copy.append(el("small", "", `${spaceKindLabel(tenant.kind)} · ${tenant.slug} · ${roleLabel(tenant.role)}`));
    row.append(copy);
    const actions = el("div", "account-actions");
    const current = isCurrent(user, tenant);
    if (current) {
        actions.append(pill("当前空间", "ok"));
    } else {
        actions.append(switchButton(tenant));
    }
    if (canDelete(user, tenant)) {
        actions.append(deleteButton(container, list, row, tenant));
    }
    row.append(actions);
    return row;
}

function switchButton(tenant) {
    const button = actionButton("进入", async () => {
        button.disabled = true;
        button.textContent = "切换中…";
        try {
            await api.switchSpace(tenant.slug);
            window.location.reload();
        } catch (failure) {
            button.disabled = false;
            button.textContent = "进入";
            toast(failure.message || "切换空间失败", true);
        }
    }, "primary");
    button.classList.add("is-mini");
    return button;
}

function canDelete(user, tenant) {
    return tenant.kind === "TEAM" && tenant.role === "ADMIN" && !isCurrent(user, tenant);
}

function isCurrent(user, tenant) {
    return tenant.id === user?.tenantId;
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
        "空间会从所有成员的空间列表和登录页消失，成员无法再进入；会话与文件会保留，可随时恢复。"));
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
        "这些空间的成员暂时无法进入，数据仍然保留。恢复后成员可以照常使用。"));
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
    copy.append(el("small", "",
        `${spaceKindLabel(tenant.kind)} · ${tenant.slug} · 删除于 ${formatDate(tenant.deletedAt)}`));
    row.append(copy);
    const actions = el("div", "account-actions");
    actions.append(pill("已删除", "danger"));
    if (tenant.role === "ADMIN") {
        const restoreButton = actionButton("恢复", () => restore(container, tenant), "primary");
        restoreButton.classList.add("is-mini");
        actions.append(restoreButton);
    }
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

function tenantForm(container) {
    const form = el("form", "settings-form");
    form.append(el("p", "settings-form-title", "创建团队空间"));
    form.append(el("p", "settings-hint",
        "空间短名会出现在登录页和切换入口里；创建后你是它的管理员，可以邀请成员。"));
    const slug = textInput("", "例如 design");
    const name = textInput("", "例如 设计组");
    const error = el("p", "settings-form-error hidden");
    const grid = el("div", "settings-form-grid");
    grid.append(
        field("空间短名", slug, "小写字母、数字与连字符，2-32 位"),
        field("空间名称", name, "成员看到的名字"));
    form.append(grid, error);
    const actions = el("div", "settings-form-actions");
    const submit = actionButton("创建空间", () => {}, "primary");
    submit.type = "submit";
    actions.append(submit);
    form.append(actions);
    form.addEventListener("submit", async (event) => {
        event.preventDefault();
        error.classList.add("hidden");
        submit.disabled = true;
        try {
            const tenant = await api.createTenant({
                slug: slug.value.trim(),
                name: name.value.trim()
            });
            toast(`已创建空间「${tenant.name}」，现在可以在上方进入`);
            await render(container);
        } catch (failure) {
            error.textContent = failure.message || "创建空间失败";
            error.classList.remove("hidden");
            submit.disabled = false;
        }
    });
    return form;
}

function memberSection(container, user, members) {
    const section = el("section", "settings-storage");
    section.append(sectionHeader(`「${user?.tenantName || "当前空间"}」的成员`,
        "只有空间管理员能管理成员；管理员可以邀请已有账号，也可以直接创建新账号。", []));
    if (!members.length) {
        section.append(el("p", "settings-hint", "还没有成员。"));
    } else {
        const list = el("div", "account-list");
        for (const member of members) {
            const row = el("div", "account-row");
            const copy = el("div", "account-row-copy");
            copy.append(el("strong", "", member.displayName || member.username));
            copy.append(el("small", "", member.username));
            row.append(copy);
            const actions = el("div", "account-actions");
            actions.append(pill(roleLabel(member.role), member.role === "ADMIN" ? "warn" : "muted"));
            actions.append(pill(STATUS_LABELS[member.status] || member.status || "—",
                member.status === "ACTIVE" ? "ok" : "danger"));
            row.append(actions);
            list.append(row);
        }
        section.append(list);
    }
    section.append(inviteForm(container));
    section.append(createMemberForm(container));
    return section;
}

function inviteForm(container) {
    const form = el("form", "settings-form");
    form.append(el("p", "settings-form-title", "邀请已有账号"));
    form.append(el("p", "settings-hint",
        "输入对方在本站的登录名，邀请后对方用原来的账号就能进入这个空间。"));
    const username = textInput("", "对方的登录名");
    const role = selectInput([
        { label: "成员", value: "MEMBER" },
        { label: "管理员", value: "ADMIN" }
    ], "MEMBER");
    const error = el("p", "settings-form-error hidden");
    const grid = el("div", "settings-form-grid");
    grid.append(field("登录名", username), field("加入后的角色", role));
    form.append(grid, error);
    const results = el("div", "account-list");
    const actions = el("div", "settings-form-actions");
    const submit = actionButton("查找并邀请", () => {}, "primary");
    submit.type = "submit";
    actions.append(submit);
    form.append(actions, results);
    form.addEventListener("submit", async (event) => {
        event.preventDefault();
        error.classList.add("hidden");
        results.replaceChildren();
        const name = username.value.trim();
        if (!name) {
            showFormError(error, "请先填写对方的登录名");
            return;
        }
        submit.disabled = true;
        try {
            const found = await api.lookupUsers(name);
            if (!found.length) {
                showFormError(error, `没有找到可邀请的账号「${name}」，或对方已在当前空间里`);
                return;
            }
            for (const account of found) {
                results.append(inviteRow(container, account, role));
            }
        } catch (failure) {
            showFormError(error, failure.message || "查找账号失败");
        } finally {
            submit.disabled = false;
        }
    });
    return form;
}

function inviteRow(container, account, role) {
    const row = el("div", "account-row");
    const copy = el("div", "account-row-copy");
    copy.append(el("strong", "", account.displayName || account.username));
    copy.append(el("small", "", account.username));
    row.append(copy);
    const actions = el("div", "account-actions");
    const button = actionButton("邀请", () => invite(container, account, role.value, button), "primary");
    button.classList.add("is-mini");
    actions.append(button);
    row.append(actions);
    return row;
}

async function invite(container, account, role, button) {
    button.disabled = true;
    try {
        await api.inviteMember(auth.currentUser().tenantId, { userId: account.id, role });
        toast(`已邀请 ${account.displayName || account.username} 加入`);
        await render(container);
    } catch (failure) {
        button.disabled = false;
        toast(failure.message || "邀请失败", true);
    }
}

function createMemberForm(container) {
    const form = el("form", "settings-form");
    form.append(el("p", "settings-form-title", "创建新账号"));
    form.append(el("p", "settings-hint",
        "新账号会直接成为当前空间的成员，同时自动获得属于自己的个人空间。"));
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
        field("初始密码", password));
    form.append(grid, error);
    const actions = el("div", "settings-form-actions");
    const submit = actionButton("创建账号", () => {}, "primary");
    submit.type = "submit";
    actions.append(submit);
    form.append(actions);
    form.addEventListener("submit", async (event) => {
        event.preventDefault();
        error.classList.add("hidden");
        submit.disabled = true;
        try {
            const created = await api.createUser({
                username: username.value.trim(),
                displayName: displayName.value.trim(),
                password: password.value,
                role: role.value
            });
            toast(`已创建账号 ${created.username}，初始密码请转交给对方`);
            await render(container);
        } catch (failure) {
            showFormError(error, failure.message || "创建账号失败");
            submit.disabled = false;
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

function showFormError(node, message) {
    node.textContent = message;
    node.classList.remove("hidden");
}

function roleLabel(role) {
    return ROLE_LABELS[role] || role || "—";
}

function spaceKindLabel(kind) {
    return KIND_LABELS[kind] || "空间";
}

function formatDate(value) {
    if (!value) {
        return "—";
    }
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleDateString("zh-CN");
}
