package com.piagent.trigger.http.system;

/**
 * 运行环境信息。
 *
 * @param version 应用版本
 * @param workspaceRoot 服务进程的默认工作目录（绝对路径）
 * @param osName 操作系统名称，仅用于展示
 */
public record SystemInfoResponse(String version, String workspaceRoot, String osName) {
}
