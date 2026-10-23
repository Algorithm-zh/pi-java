/**
 * 技能设置页。
 *
 * 列表只展示目录元数据；“查看正文”按需请求内容，避免一次性把大量
 * Markdown 传给浏览器。重新扫描会绕过服务端目录缓存。
 */

import * as api from "./api.js";
import { el } from "./render.js";
import {
    actionButton, codeBlock, emptyState, loadingState, sectionHeader
} from "./settings-ui.js";

let containerRef = null;

/**
 * 渲染技能列表。
 *
 * @param {HTMLElement} container 面板容器
 */
export async function render(container) {
    containerRef = container;
    const skills = await api.listSkills(false);
    container.replaceChildren();
    container.append(sectionHeader("技能目录",
        "技能索引进 system prompt，正文只在需要时读取；名称可在消息中用 /skill:名字 引用。", [
            actionButton("重新扫描", () => refresh(), "primary")
        ]));
    container.append(sourceHint());
    if (!skills.length) {
        container.append(emptyState("还没有发现技能",
            "在 ~/.pi-java/skills/<name>/SKILL.md 创建技能，"
            + "或通过 pi.agent.skills 配置额外目录。"));
        return;
    }
    const list = el("div", "skill-list");
    for (const skill of skills) {
        list.append(skillCard(skill));
    }
    container.append(list);
}

function sourceHint() {
    const box = el("div", "settings-callout");
    box.append(el("strong", "", "技能从哪里来"));
    box.append(el("p", "",
        "服务启动时扫描配置目录。每个技能是一个子目录，入口固定为 SKILL.md，"
        + "frontmatter 提供 name 与 description，正文按需加载。"));
    box.append(codeBlock("~/.pi-java/skills/<skill-name>/SKILL.md"));
    return box;
}

function skillCard(skill) {
    const card = el("article", "skill-card");
    const head = el("div", "skill-card-head");
    head.append(el("span", "skill-mark", "SK"));
    const copy = el("div", "skill-card-title");
    copy.append(el("strong", "", skill.name));
    copy.append(el("small", "", skill.description || "没有描述"));
    head.append(copy);
    const content = el("pre", "settings-code skill-content");
    const toggle = actionButton("查看正文", async () => {
        if (!content.textContent) {
            content.replaceChildren();
            content.append(loadingState("读取正文…"));
            try {
                const body = await api.getSkillContent(skill.name);
                content.textContent = body.content || "（空文件）";
            } catch (error) {
                content.textContent = error.message || "读取失败";
            }
        } else {
            content.textContent = "";
        }
        content.classList.toggle("hidden", !content.textContent);
    }, "mini");
    head.append(toggle);
    card.append(head);
    card.append(el("p", "skill-path", skill.path));
    card.append(el("small", "skill-source", `来源：${skill.source || "未知"}`));
    content.classList.add("hidden");
    card.append(content);
    return card;
}

async function refresh() {
    containerRef.replaceChildren(loadingState("正在重新扫描技能目录…"));
    try {
        await api.listSkills(true);
        await render(containerRef);
    } catch (error) {
        containerRef.replaceChildren(emptyState("重新扫描失败", error.message || "请求失败"));
    }
}
