/**
 * 子代理领域包。
 *
 * <p>负责两件事：定义子代理的<b>边界</b>（{@link com.piagent.domain.subagent.SubagentPolicy}、
 * {@link com.piagent.domain.subagent.SubagentRequest}）与<b>结果形态</b>
 * （{@link com.piagent.domain.subagent.SubagentResult}）。真正的执行编排在应用层
 * {@code application.context} 同级的位置（{@code SubagentService}），因为同步执行需要
 * 复用 Agent Loop 的 run 生命周期。</p>
 *
 * <p>为什么放在独立限界上下文：子代理引入了“会话树 + 权限收敛 + 取消级联”这组规则，
 * 它们与单次 run 的状态机不同；单独成包可以避免把会话上下文撑大。</p>
 */
package com.piagent.domain.subagent;
