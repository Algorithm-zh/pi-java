/**
 * CLI 触发层：picocli 命令、终端渲染与审批交互。
 *
 * <p>与 HTTP 触发层并列，只调用应用层用例；不直接访问仓储、Mapper 或基础设施。
 * Web 与 CLI 共用同一个 Spring 容器，CLI 模式以 {@code WebApplicationType.NONE}
 * 启动，不监听端口。</p>
 */
package com.piagent.trigger.cli;
