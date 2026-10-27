package com.piagent.application.attachment;

/**
 * 附件回收结果。
 *
 * @param scanned 扫描的附件数量
 * @param deleted 删除的附件数量
 * @param freedBytes 释放的字节数
 */
public record AttachmentReclaimResult(int scanned, int deleted, long freedBytes) {
}
