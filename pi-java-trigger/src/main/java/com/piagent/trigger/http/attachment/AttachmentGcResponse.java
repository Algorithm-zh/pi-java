package com.piagent.trigger.http.attachment;

import com.piagent.application.attachment.AttachmentReclaimResult;

/**
 * 附件回收响应。
 *
 * @param scanned 扫描的附件数量
 * @param deleted 删除的附件数量
 * @param freedBytes 释放的字节数
 */
public record AttachmentGcResponse(int scanned, int deleted, long freedBytes) {

    /**
     * 从应用层结果构造响应。
     *
     * @param result 回收结果
     * @return 响应
     */
    public static AttachmentGcResponse from(AttachmentReclaimResult result) {
        return new AttachmentGcResponse(result.scanned(), result.deleted(), result.freedBytes());
    }
}
