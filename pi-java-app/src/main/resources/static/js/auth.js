/**
 * 登录门禁：首次初始化管理员、登录、会话过期与退出。
 *
 * 门禁覆盖整个应用外壳：未登录时不渲染任何会话数据，避免界面先闪出
 * 401 错误再跳转。令牌保存在 HttpOnly Cookie 里，脚本不接触明文。
 */

import * as api from "./api.js";

let user = null;
let gate;
let appShell;
let handlers = {};

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
    document.getElementById("auth-setup-form")
        .addEventListener("submit", submitSetup);
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
    try {
        const session = await api.login({
            tenantSlug: inputValue("login-tenant"),
            username: inputValue("login-username"),
            password: document.getElementById("login-password").value
        });
        document.getElementById("login-password").value = "";
        await handlers.onReady(enter(session.user));
    } catch (failure) {
        setError(error, failure.message || "登录失败，请重试");
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
    renderAccount(account);
    gate.classList.add("hidden");
    appShell.classList.remove("hidden");
    return account;
}

function renderAccount(account) {
    const display = account.displayName || account.username;
    document.getElementById("account-name").textContent = display;
    document.getElementById("account-tenant").textContent =
        `${account.tenantName} · ${account.role === "ADMIN" ? "管理员" : "成员"}`;
    document.getElementById("account-avatar").textContent =
        display.trim().slice(0, 1).toUpperCase();
    document.getElementById("account-card").classList.remove("hidden");
}

function show(name) {
    gate.classList.remove("hidden");
    appShell.classList.add("hidden");
    document.getElementById("auth-checking").classList.add("hidden");
    document.getElementById("auth-login").classList.toggle("hidden", name !== "login");
    document.getElementById("auth-setup").classList.toggle("hidden", name !== "setup");
    document.getElementById(name === "login" ? "login-username" : "setup-username").focus();
}

function showLogin(lead) {
    document.getElementById("auth-login-lead").textContent = lead;
    show("login");
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
