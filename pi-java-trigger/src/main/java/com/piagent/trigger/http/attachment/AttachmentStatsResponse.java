package com.piagent.trigger.http.attachment;

import com.piagent.application.attachment.AttachmentStats;

/**
 * 附件存储统计响应。
 *
 * @param count 附件数量
 * @param bytes 已占用字节数
 * @param quotaBytes 总配额，0 表示不限制
 */
public record AttachmentStatsResponse(int count, long bytes, long quotaBytes) {

    /**
     * 从应用层结果构造响应。
     *
     * @param stats 统计
     * @return 响应
     */
    public static AttachmentStatsResponse from(AttachmentStats stats) {
        return new AttachmentStatsResponse(stats.count(), stats.bytes(), stats.quotaBytes());
    }
}
