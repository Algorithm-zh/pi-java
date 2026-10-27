/**
 * 系统信息页。
 *
 * 只展示公开的运行环境与安全边界，不暴露数据库连接、密钥值或 JVM
 * 内部配置；工作目录可以一键复制，方便配置工具策略。
 */

import * as api from "./api.js";
import { el } from "./render.js";
import { toast } from "./store.js";
import {
    actionButton, copyButton, definitionList, sectionHeader
} from "./settings-ui.js";

/**
 * 渲染系统信息。
 *
 * @param {HTMLElement} container 面板容器
 */
export async function render(container) {
    const [info, stats] = await Promise.all([
        api.systemInfo(), api.attachmentStats()
    ]);
    container.replaceChildren();
    container.append(sectionHeader("运行环境",
        "当前是本机演示形态：只监听回环地址，不提供登录与多用户隔离。", []));
    container.append(definitionList([
        ["应用版本", info.version],
        ["操作系统", info.osName],
        ["进程工作目录", info.workspaceRoot]
    ]));
    const copy = el("div", "settings-inline-actions");
    copy.append(copyButton(info.workspaceRoot, "复制工作目录路径"));
    container.append(copy);
    container.append(storageSection(stats, container));
    container.append(securityNotes());
}

function storageSection(stats, container) {
    const section = el("section", "settings-storage");
    section.append(sectionHeader("附件存储",
        "图片保存在数据目录的 attachments 下；未被任何会话引用的图片会按计划自动清理。", [
            actionButton("清理未引用附件", () => reclaim(container), "ghost")
        ]));
    section.append(definitionList([
        ["已保存", `${stats.count} 张`],
        ["占用空间", formatBytes(stats.bytes)],
        ["总配额", stats.quotaBytes > 0 ? formatBytes(stats.quotaBytes) : "不限制"]
    ]));
    return section;
}

async function reclaim(container) {
    try {
        const result = await api.reclaimAttachments();
        if (result.deleted > 0) {
            toast(`已清理 ${result.deleted} 个附件，释放 ${formatBytes(result.freedBytes)}`);
        } else {
            toast("没有需要清理的附件");
        }
        await render(container);
    } catch (error) {
        toast(error.message || "清理失败", true);
    }
}

function formatBytes(bytes) {
    if (!Number.isFinite(bytes) || bytes <= 0) {
        return "0 KB";
    }
    if (bytes >= 1024 * 1024 * 1024) {
        return `${(bytes / 1024 / 1024 / 1024).toFixed(1)} GB`;
    }
    if (bytes >= 1024 * 1024) {
        return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
    }
    return `${Math.max(1, Math.round(bytes / 1024))} KB`;
}

function securityNotes() {
    const box = el("div", "settings-callout");
    box.append(el("strong", "", "安全边界"));
    const list = el("ul", "settings-note-list");
    for (const note of [
        "服务默认绑定 127.0.0.1。若改成 0.0.0.0，请自行在反向代理层加认证。",
        "模型密钥只从环境变量读取；数据库只保存变量名，接口只返回是否已配置。",
        "文件工具被限制在会话工作目录内，越界路径会直接失败。",
        "写文件、编辑与命令执行默认进入审批，除非工具策略显式改为 ALLOW。",
        "插件代码启用后与宿主同权限运行，只安装你信任的 jar。"
    ]) {
        list.append(el("li", "", note));
    }
    box.append(list);
    return box;
}
