/**
 * 工具策略设置页。
 *
 * 策略按 (作用域, 工作区, 工具名) 幂等覆盖：同一组合再次保存就是更新，
 * 没有独立删除接口。工作区视图只展示“工作区规则 + 用户全局规则”。
 */

import * as api from "./api.js";
import { el } from "./render.js";
import { state, toast } from "./store.js";
import {
    actionButton, codeBlock, emptyState, field, numberInput, pill, sectionHeader, selectInput,
    textInput
} from "./settings-ui.js";

const EFFECTS = [
    { value: "ALLOW", label: "ALLOW · 直接执行" },
    { value: "ASK", label: "ASK · 需要审批" },
    { value: "DENY", label: "DENY · 直接拒绝" }
];

const APPROVALS = [
    { value: "ONCE", label: "ONCE · 每次审批" },
    { value: "SESSION", label: "SESSION · 本会话一次" },
    { value: "ALWAYS", label: "ALWAYS · 一直允许" }
];

const RISKS = [
    { value: "", label: "沿用工具默认" },
    { value: "LOW", label: "LOW" },
    { value: "MEDIUM", label: "MEDIUM" },
    { value: "HIGH", label: "HIGH" },
    { value: "CRITICAL", label: "CRITICAL" }
];

let containerRef = null;
let cwdValue = "";
let tools = [];
let policies = [];
let draft = null;

/**
 * 渲染工具策略页。
 *
 * @param {HTMLElement} container 面板容器
 */
export async function render(container) {
    containerRef = container;
    if (!cwdValue) {
        cwdValue = state.session?.cwd || state.system?.workspaceRoot || "";
    }
    const [toolList, policyList] = await Promise.all([
        api.listTools(),
        api.listToolPolicies(cwdValue)
    ]);
    tools = toolList;
    policies = policyList;
    container.replaceChildren();
    container.append(sectionHeader("工具策略",
        "工作区规则优先于用户全局规则；同一条规则再次保存会覆盖并递增版本。", [
            actionButton("刷新", () => reload(), "mini")
        ]));
    container.append(guide());
    container.append(workspaceBar());
    container.append(editor());
    container.append(policyListBlock());
}

function guide() {
    const box = el("div", "settings-callout");
    box.append(el("strong", "", "效果与审批模式"));
    box.append(el("p", "",
        "ALLOW 直接执行；ASK 进入审批弹窗；DENY 立即拒绝。审批模式只对 ASK 生效："
        + "ONCE 每次询问，SESSION 本会话同类调用只问一次，ALWAYS 一直允许。"));
    box.append(codeBlock("优先级：工作区规则 > 用户全局规则 > 工具默认"));
    return box;
}

function workspaceBar() {
    const box = el("div", "settings-inline-row policy-workspace");
    const input = textInput(cwdValue, "工作目录绝对路径");
    const load = actionButton("加载此工作区", async () => {
        cwdValue = input.value.trim();
        await reload();
    }, "primary");
    box.append(field("工作区目录", input, "只影响工作区策略的展示与保存"), load);
    return box;
}

function editor() {
    const form = el("form", "settings-form policy-editor");
    form.append(el("h4", "settings-form-title", draft ? "编辑策略" : "新建策略"));
    const scope = selectInput([
        { value: "WORKSPACE", label: "WORKSPACE · 当前工作区" },
        { value: "USER", label: "USER · 跨工作区" }
    ], draft?.scope || "WORKSPACE");
    const toolOptions = tools.map((item) => ({
        value: item.name,
        label: `${item.name} · 默认 ${item.risk}`
    }));
    if (draft?.toolName && !toolOptions.some((item) => item.value === draft.toolName)) {
        toolOptions.push({ value: draft.toolName, label: `${draft.toolName} · 已安装工具` });
    }
    const tool = selectInput(toolOptions, draft?.toolName);
    const effect = selectInput(EFFECTS, draft?.effect || "ASK");
    const approval = selectInput(APPROVALS, draft?.approvalMode || "ONCE");
    const risk = selectInput(RISKS, draft?.riskLevel || "");
    const timeout = numberInput(draft?.timeoutMs ?? "", 1);
    const error = el("p", "settings-form-error hidden");

    const grid = el("div", "settings-form-grid");
    grid.append(field("作用域", scope), field("工具", tool));
    grid.append(field("效果", effect), field("审批模式", approval));
    grid.append(field("风险覆盖", risk), field("超时毫秒", timeout, "留空沿用工具默认"));
    form.append(grid, error);

    const actions = el("div", "settings-form-actions");
    if (draft) {
        actions.append(actionButton("取消编辑", () => {
            draft = null;
            reload();
        }));
    }
    const submit = actionButton(draft ? "保存修改" : "保存策略", () => {}, "primary");
    submit.type = "submit";
    actions.append(submit);
    form.append(actions);

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
            await api.upsertToolPolicy(payload);
            toast(`已保存 ${payload.toolName} 的策略`);
            draft = null;
            await reload();
        } catch (requestError) {
            error.textContent = requestError.message || "保存失败";
            error.classList.remove("hidden");
            submit.disabled = false;
        }
    });
    return form;

    function buildPayload() {
        if (!tool.value) {
            return "请选择工具";
        }
        const timeoutValue = timeout.value.trim();
        const parsed = timeoutValue ? Number(timeoutValue) : null;
        if (timeoutValue && (!Number.isFinite(parsed) || parsed < 1)) {
            return "超时必须是正整数毫秒";
        }
        const payload = {
            scope: scope.value,
            toolName: tool.value,
            effect: effect.value,
            approvalMode: approval.value
        };
        if (scope.value === "WORKSPACE") {
            if (!cwdValue) {
                return "工作区作用域需要填写目录";
            }
            payload.cwd = cwdValue;
        }
        if (risk.value) {
            payload.riskLevel = risk.value;
        }
        if (parsed !== null) {
            payload.timeoutMs = parsed;
        }
        return payload;
    }
}

function policyListBlock() {
    const block = el("div", "policy-block");
    block.append(el("h4", "settings-form-title", `当前可见策略（${policies.length}）`));
    if (!policies.length) {
        block.append(emptyState("没有自定义策略",
            "所有工具都使用内置默认值。保存一条 ASK 或 DENY 规则即可覆盖。"));
        return block;
    }
    const list = el("div", "policy-list");
    for (const policy of policies) {
        list.append(policyCard(policy));
    }
    block.append(list);
    return block;
}

function policyCard(policy) {
    const card = el("article", "policy-card");
    const head = el("div", "policy-card-head");
    head.append(el("strong", "", policy.toolName));
    head.append(pill(policy.effect, effectTone(policy.effect)));
    head.append(pill(policy.scope === "USER" ? "用户全局" : "工作区", "muted"));
    card.append(head);
    const meta = el("dl", "settings-meta");
    meta.append(metaRow("工作区键", policy.workspaceKey));
    meta.append(metaRow("审批模式", policy.approvalMode));
    meta.append(metaRow("风险覆盖", policy.riskLevel));
    meta.append(metaRow("超时", policy.timeoutMs ? `${policy.timeoutMs} ms` : "默认"));
    meta.append(metaRow("版本", `v${policy.version}`));
    card.append(meta);
    card.append(actionButton("载入编辑器", () => {
        draft = {
            scope: policy.scope,
            toolName: policy.toolName,
            effect: policy.effect,
            approvalMode: policy.approvalMode,
            riskLevel: policy.riskLevel,
            timeoutMs: policy.timeoutMs
        };
        reload();
    }, "mini"));
    return card;
}

function metaRow(label, value) {
    const row = el("div");
    row.append(el("dt", "", label), el("dd", "", String(value ?? "—")));
    return row;
}

function effectTone(effect) {
    return { ALLOW: "ok", ASK: "warn", DENY: "danger" }[effect] || "muted";
}

async function reload() {
    if (!containerRef) {
        return;
    }
    containerRef.replaceChildren();
    try {
        await render(containerRef);
    } catch (error) {
        toast(error.message || "加载策略失败", true);
    }
}
