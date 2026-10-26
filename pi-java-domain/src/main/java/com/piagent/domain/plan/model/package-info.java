/**
 * 任务计划与待办模型。
 *
 * <p>计划是会话日志上的快照（PLAN entry），不是可变聚合；服务层负责
 * “读取分支上最后一条”与“全量替换后追加新快照”。</p>
 */
package com.piagent.domain.plan.model;
