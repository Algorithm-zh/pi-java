/**
 * 会话上下文：树形会话日志、分支投影与上下文重建。
 *
 * <p>聚合根为 {@code ChatSession}，会话 entry 是唯一事实源；压缩、审批决策、
 * 运行摘要都作为 entry 追加，不另建对话数组。
 */
package com.piagent.domain.session;
