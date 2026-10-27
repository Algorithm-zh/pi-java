package com.piagent.trigger.http.session;

import java.util.List;

/**
 * 发送消息请求。
 *
 * @param text 用户文本，可为空串（只发图片时）
 * @param attachmentIds 图片附件 ID，可为空
 */
public record SendMessageRequest(String text, List<String> attachmentIds) {
}
