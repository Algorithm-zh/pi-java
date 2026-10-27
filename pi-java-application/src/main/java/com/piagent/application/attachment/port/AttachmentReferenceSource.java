package com.piagent.application.attachment.port;

import java.util.Set;

/**
 * 附件引用来源。
 *
 * <p>返回当前会话历史中仍被消息引用的附件 ID；回收任务据此判断哪些附件
 * 已经没有任何记录指向，可以安全删除。</p>
 */
public interface AttachmentReferenceSource {

    /**
     * 收集仍被引用的附件 ID。
     *
     * @return 附件 ID 集合
     */
    Set<String> referencedIds();
}
