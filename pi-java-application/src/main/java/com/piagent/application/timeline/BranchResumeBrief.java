package com.piagent.application.timeline;

import java.util.Map;

/**
 * 切换后写入会话的结构化简报。
 *
 * @param summary 不超过 400 字的摘要
 * @param details 结构化细节，写入 {@code BranchSummaryPayload.details}
 */
public record BranchResumeBrief(String summary, Map<String, Object> details) {
}
