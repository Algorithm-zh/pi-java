/**
 * Agent 运行时上下文：一次 run 的状态机、turn/step 编排与停止原因。
 *
 * <p>运行态只存在于内存，不持久化；run 结束后向会话追加运行摘要 entry。
 */
package com.piagent.domain.agent;
