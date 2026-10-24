package com.piagent.trigger.cli;

import java.util.Map;

/**
 * 审批提示端口。
 *
 * <p>只负责“问用户并返回决策”，决策提交、原因与退出码仍由执行器统一处理，
 * 避免交互式界面和行模式各自实现一遍审批状态机。</p>
 */
@FunctionalInterface
public interface ApprovalPrompt {

    /**
     * 询问一次审批。
     *
     * @param data 审批事件数据（已脱敏）
     * @return 用户决策
     */
    Answer ask(Map<String, Object> data);

    /**
     * 一次审批回答。
     *
     * @param approved 是否批准
     * @param noInput 是否因没有输入而拒绝
     */
    record Answer(boolean approved, boolean noInput) {

        /**
         * 批准。
         *
         * @return 批准回答
         */
        public static Answer approve() {
            return new Answer(true, false);
        }

        /**
         * 明确拒绝。
         *
         * @return 拒绝回答
         */
        public static Answer deny() {
            return new Answer(false, false);
        }

        /**
         * 无输入，默认拒绝。
         *
         * @return 无输入回答
         */
        public static Answer missingInput() {
            return new Answer(false, true);
        }
    }
}
