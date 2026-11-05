/**
 * 登录门禁：首次初始化管理员、登录、自助注册、会话过期与退出。
 *
 * 门禁覆盖整个应用外壳：未登录时不渲染任何会话数据，避免界面先闪出
 * 401 错误再跳转。令牌保存在 HttpOnly Cookie 里，脚本不接触明文。
 * 登录只需账号密码，空间由服务端自动定位；只有同名账号出现在多个空间时
 * 才让用户点选，并记住上次进入的空间。
 */

import * as api from "./api.js";

const LAST_TENANT_KEY = "pi-last-tenant";
const KIND_LABELS = { DEFAULT: "默认空间", PERSONAL: "个人空间", TEAM: "团队空间" };
const ROLE_LABELS = { ADMIN: "管理员", MEMBER: "成员" };

let user = null;
let gate;
let appShell;
let handlers = {};
let registrationEnabled = true;
let pickerVisible = false;

/**
 * 绑定门禁事件。必须在页面加载后调用一次。
 *
 * @param {{onReady: function(object): Promise<void>, onExpired?: function(): void}} callbacks
 *     登录成功与过期回调
 */
export function init(callbacks) {
    handlers = callbacks;
    gate = document.getElementById("auth-gate");
    appShell = document.getElementById("app-shell");
    document.getElementById("auth-login-form")
        .addEventListener("submit", submitLogin);
    document.getElementById("auth-register-form")
        .addEventListener("submit", submitRegister);
    document.getElementById("auth-setup-form")
        .addEventListener("submit", submitSetup);
    document.getElementById("show-register")
        .addEventListener("click", () => show("register"));
    document.getElementById("show-login")
        .addEventListener("click", () => show("login"));
    document.getElementById("logout-button").addEventListener("click", logout);
    window.addEventListener("pi-auth-required", expire);
}

/**
 * 当前登录账号；未登录时为 null。
 *
 * @returns {object|null} 账号与租户信息
 */
export function currentUser() {
    return user;
}

/**
 * 检查登录状态；未登录时展示门禁。
 *
 * @returns {Promise<object|null>} 已登录账号；需要登录时为 null
 */
export async function ensureSession() {
    try {
        const state = await api.authState();
        applyCapabilities(state);
        if (!state.initialized) {
            show("setup");
            return null;
        }
        if (!state.authenticated) {
            show("login");
            return null;
        }
        return enter(state.user);
    } catch (error) {
        show("login");
        setError(errorNode("login"), `无法连接服务端：${error.message || "未知错误"}`);
        return null;
    }
}

async function submitLogin(event) {
    event.preventDefault();
    const error = errorNode("login");
    clearError(error);
    const button = document.getElementById("login-submit");
    button.disabled = true;
    const credentials = (tenantSlug) => ({
        tenantSlug,
        username: inputValue("login-username"),
        password: document.getElementById("login-password").value
    });
    try {
        let session;
        try {
            session = await api.login(credentials(pickerVisible ? inputValue("login-tenant") : ""));
        } catch (failure) {
            const choices = failure.details?.tenants || [];
            const remembered = lastTenant(credentials().username);
            if (failure.code !== "AUTH_TENANT_REQUIRED" || pickerVisible
                    || !remembered || !choices.some((choice) => choice.slug === remembered)) {
                throw failure;
            }
            // 账号属于多个空间时优先回到上次的空间，少一次点选。
            session = await api.login(credentials(remembered));
        }
        document.getElementById("login-password").value = "";
        await handlers.onReady(enter(session.user));
    } catch (failure) {
        if (failure.code === "AUTH_TENANT_REQUIRED") {
            offerTenantChoice(failure.details?.tenants || [], error, inputValue("login-username"));
        } else {
            setError(error, failure.message || "登录失败，请重试");
        }
    } finally {
        button.disabled = false;
    }
}

async function submitRegister(event) {
    event.preventDefault();
    const error = errorNode("register");
    clearError(error);
    const password = document.getElementById("register-password").value;
    if (password !== document.getElementById("register-confirm").value) {
        setError(error, "两次输入的密码不一致");
        return;
    }
    const button = document.getElementById("register-submit");
    button.disabled = true;
    try {
        const session = await api.register({
            username: inputValue("register-username"),
            displayName: inputValue("register-display"),
            password
        });
        document.getElementById("register-password").value = "";
        document.getElementById("register-confirm").value = "";
        await handlers.onReady(enter(session.user));
    } catch (failure) {
        setError(error, failure.message || "创建账号失败，请重试");
    } finally {
        button.disabled = false;
    }
}

async function submitSetup(event) {
    event.preventDefault();
    const error = errorNode("setup");
    clearError(error);
    const password = document.getElementById("setup-password").value;
    if (password !== document.getElementById("setup-confirm").value) {
        setError(error, "两次输入的密码不一致");
        return;
    }
    const button = document.getElementById("setup-submit");
    button.disabled = true;
    try {
        const session = await api.setupAdmin({
            username: inputValue("setup-username"),
            displayName: inputValue("setup-display"),
            password
        });
        document.getElementById("setup-password").value = "";
        document.getElementById("setup-confirm").value = "";
        await handlers.onReady(enter(session.user));
    } catch (failure) {
        setError(error, failure.message || "初始化失败，请重试");
    } finally {
        button.disabled = false;
    }
}

async function logout() {
    try {
        await api.logout();
    } catch (error) {
        // 令牌可能已过期；无论服务端结果如何都回到登录界面。
    }
    window.location.reload();
}

function expire() {
    if (!user) {
        return;
    }
    user = null;
    handlers.onExpired?.();
    showLogin("登录状态已失效，请重新登录");
}

function enter(account) {
    user = account;
    resetTenantPicker();
    rememberTenant(account.username, account.tenantSlug);
    renderAccount(account);
    gate.classList.add("hidden");
    appShell.classList.remove("hidden");
    return account;
}

function rememberTenant(username, slug) {
    try {
        if (username && slug) {
            localStorage.setItem(`${LAST_TENANT_KEY}:${username}`, slug);
        }
    } catch {
        // 隐私模式下 localStorage 可能不可用；记住空间只是便利，失败不影响登录。
    }
}

function lastTenant(username) {
    try {
        return username ? localStorage.getItem(`${LAST_TENANT_KEY}:${username}`) : null;
    } catch {
        return null;
    }
}

function applyCapabilities(state) {
    registrationEnabled = state.registrationEnabled !== false;
    document.getElementById("auth-register-entry")
        .classList.toggle("hidden", !registrationEnabled);
}

/**
 * 同一账号出现在多个空间时，把空间列成按钮让用户直接点选。
 *
 * @param {Array<{slug: string, name: string}>} tenants 候选空间
 * @param {HTMLElement} error 错误节点
 * @param {string} username 登录名，用于读取该账号上次的空间
 */
function offerTenantChoice(tenants, error, username) {
    if (!tenants.length) {
        setError(error, "该账号属于多个空间，请填写空间短名后重试");
        revealPicker();
        return;
    }
    pickerVisible = true;
    const picker = document.getElementById("login-tenant-picker");
    const choices = document.getElementById("login-tenant-choices");
    const input = document.getElementById("login-tenant");
    const remembered = lastTenant(username);
    choices.replaceChildren();
    for (const tenant of tenants) {
        const choice = document.createElement("button");
        choice.type = "button";
        choice.className = "auth-choice";
        const name = document.createElement("strong");
        name.textContent = tenant.name;
        const slug = document.createElement("small");
        slug.textContent = `${KIND_LABELS[tenant.kind] || "空间"} · ${tenant.slug}`;
        choice.append(name, slug);
        if (tenant.slug === remembered) {
            choice.classList.add("is-last");
        }
        choice.addEventListener("click", () => {
            input.value = tenant.slug;
            document.getElementById("auth-login-form").requestSubmit();
        });
        choices.append(choice);
    }
    if (remembered && tenants.some((tenant) => tenant.slug === remembered)) {
        input.value = remembered;
    }
    picker.classList.remove("hidden");
    clearError(error);
    document.getElementById("auth-login-lead").textContent = "请选择要进入的空间。";
    document.getElementById("login-submit").textContent = "进入所选空间";
    input.focus();
}

function revealPicker() {
    pickerVisible = true;
    document.getElementById("login-tenant-picker").classList.remove("hidden");
    document.getElementById("auth-login-lead").textContent = "请填写要进入的空间。";
    document.getElementById("login-submit").textContent = "进入所选空间";
}

function resetTenantPicker() {
    pickerVisible = false;
    document.getElementById("login-tenant-picker").classList.add("hidden");
    document.getElementById("login-tenant-choices").replaceChildren();
    document.getElementById("login-tenant").value = "";
    document.getElementById("auth-login-lead").textContent =
        "输入账号和密码，自动回到上次的空间。";
    const submit = document.getElementById("login-submit");
    submit.textContent = "登录";
}

function renderAccount(account) {
    const display = account.displayName || account.username;
    document.getElementById("account-name").textContent = display;
    const tenant = document.getElementById("account-tenant");
    const kind = KIND_LABELS[account.tenantKind] || "空间";
    tenant.textContent = `${account.tenantName} · ${ROLE_LABELS[account.role] || account.role}`;
    tenant.title = `${kind} · ${account.tenantName}（${account.tenantSlug}）`;
    document.getElementById("account-avatar").textContent =
        display.trim().slice(0, 1).toUpperCase();
    document.getElementById("account-card").classList.remove("hidden");
}

function show(name) {
    gate.classList.remove("hidden");
    appShell.classList.add("hidden");
    document.getElementById("auth-checking").classList.add("hidden");
    document.getElementById("auth-login").classList.toggle("hidden", name !== "login");
    document.getElementById("auth-register").classList.toggle("hidden", name !== "register");
    document.getElementById("auth-setup").classList.toggle("hidden", name !== "setup");
    if (name !== "login") {
        resetTenantPicker();
    }
    const focus = name === "register" ? "register-username"
        : name === "setup" ? "setup-username" : "login-username";
    document.getElementById(focus).focus();
}

function showLogin(lead) {
    show("login");
    resetTenantPicker();
    document.getElementById("auth-login-lead").textContent = lead;
}

function inputValue(id) {
    return document.getElementById(id).value.trim();
}

function errorNode(name) {
    return document.getElementById(`${name}-error`);
}

function setError(node, message) {
    node.textContent = message;
    node.classList.remove("hidden");
}

function clearError(node) {
    node.textContent = "";
    node.classList.add("hidden");
}
