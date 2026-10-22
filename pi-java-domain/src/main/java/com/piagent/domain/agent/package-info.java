/**
 * agent 运行时上下文：只存在于进程内的 run 状态与事件。
 *
 * <p>该包不持有会话历史；所有需要持久化的事实通过 session 上下文的 entry 落库。
 * 依赖方向保持“agent → session / shared”，不反向依赖 session。
 */
package com.piagent.domain.agent;
