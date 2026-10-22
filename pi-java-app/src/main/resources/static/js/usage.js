import { formatDuration, formatNumber, lastRunSummary } from "./render.js";

const dom = {
    panel: document.getElementById("usage"),
    turns: document.getElementById("usage-turns"),
    tools: document.getElementById("usage-tools"),
    input: document.getElementById("usage-input"),
    output: document.getElementById("usage-output"),
    total: document.getElementById("usage-total"),
    duration: document.getElementById("usage-duration")
};

/**
 * 展示一次 run 的汇总用量。
 *
 * @param {object} extra RUN_SUMMARY entry 的 extra 字段
 */
export function show(extra) {
    const usage = extra.usage || {};
    dom.panel.classList.remove("hidden");
    dom.turns.textContent = extra.turns ?? "—";
    dom.tools.textContent = extra.toolCalls ?? "—";
    dom.input.textContent = formatNumber(usage.input);
    dom.output.textContent = formatNumber(usage.output);
    dom.total.textContent = formatNumber(usage.totalTokens);
    dom.duration.textContent = extra.durationMs === undefined
        ? "" : formatDuration(extra.durationMs);
}

/**
 * 展示流式过程中的累计用量。
 *
 * @param {{input: number, output: number, totalTokens: number}} live 累计值
 */
export function showLive(live) {
    show({ usage: live, turns: "—", toolCalls: "—" });
}

/**
 * 从会话 entry 中恢复最近一次 run 摘要。
 *
 * @param {Array<object>} entries 会话 entry
 */
export function restore(entries) {
    const summary = lastRunSummary(entries);
    if (summary) {
        show(summary);
    } else {
        hide();
    }
}

export function hide() {
    dom.panel.classList.add("hidden");
}
