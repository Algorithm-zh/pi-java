package com.piagent.domain.context;

/**
 * 压缩摘要的固定小节。
 *
 * <p>固定小节让摘要既可被模型理解，也可被人工审阅与测试断言：
 * 前六节描述任务状态，最后一节承载“继续执行必须知道”的细节（文件、命令、错误）。
 * 小节顺序即输出顺序，不依赖 Map 的迭代顺序。</p>
 */
public enum SummarySection {

    /** 目标：用户想完成什么。 */
    GOAL("目标"),

    /** 约束：用户明确的限制、规范与偏好。 */
    CONSTRAINTS("约束"),

    /** 进展：已经完成的工作与验证结果。 */
    PROGRESS("进展"),

    /** 决策：已经做出且不应反复的关键选择。 */
    DECISIONS("决策"),

    /** 下一步：尚未完成、需要继续做的事。 */
    NEXT_STEPS("下一步"),

    /** 关键上下文：继续工作必需的文件、命令、错误与数据。 */
    CRITICAL_CONTEXT("关键上下文");

    private final String title;

    SummarySection(String title) {
        this.title = title;
    }

    /**
     * 小节标题（中文，用于提示词与最终摘要）。
     *
     * @return 标题
     */
    public String title() {
        return title;
    }
}
