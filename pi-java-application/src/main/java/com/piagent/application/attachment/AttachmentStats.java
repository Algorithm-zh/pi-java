package com.piagent.application.attachment;

/**
 * 附件存储统计。
 *
 * @param count 已保存附件数量
 * @param bytes 已占用字节数
 * @param quotaBytes 总配额，0 表示不限制
 */
public record AttachmentStats(int count, long bytes, long quotaBytes) {
}
