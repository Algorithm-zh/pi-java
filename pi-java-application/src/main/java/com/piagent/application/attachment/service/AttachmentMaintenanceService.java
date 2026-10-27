package com.piagent.application.attachment.service;

import com.piagent.application.attachment.AttachmentReclaimResult;
import com.piagent.application.attachment.port.AttachmentReferenceSource;
import com.piagent.application.attachment.port.AttachmentStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 附件回收服务。
 *
 * <p>只删除既没有任何会话消息引用、又超过宽限期的附件。宽限期避免把刚上传、
 * 还没随消息发送的图片删掉。</p>
 */
@Service
public class AttachmentMaintenanceService {

    private final AttachmentStore store;
    private final AttachmentReferenceSource references;
    private final Clock clock;
    private final Duration minAge;

    /**
     * 创建回收服务。
     *
     * @param store 附件存储
     * @param references 引用来源
     * @param clock 时钟
     * @param minAge 未引用附件的保留时长
     */
    public AttachmentMaintenanceService(AttachmentStore store,
            AttachmentReferenceSource references, Clock clock,
            @Value("${pi.attachments.gc-min-age:1h}") Duration minAge) {
        this.store = Objects.requireNonNull(store, "store 不能为空");
        this.references = Objects.requireNonNull(references, "references 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
        this.minAge = minAge == null || minAge.isNegative() ? Duration.ZERO : minAge;
    }

    /**
     * 按配置的宽限期回收未引用附件。
     *
     * @return 回收结果
     */
    public AttachmentReclaimResult reclaim() {
        Set<String> referenced = references.referencedIds();
        Instant cutoff = clock.instant().minus(minAge);
        int scanned = 0;
        int deleted = 0;
        long freed = 0;
        for (AttachmentStore.StoredAttachment stored : store.list()) {
            scanned++;
            if (referenced.contains(stored.metadata().id())
                    || stored.createdAt().isAfter(cutoff)) {
                continue;
            }
            if (store.delete(stored.metadata().id())) {
                deleted++;
                freed += stored.metadata().size();
            }
        }
        return new AttachmentReclaimResult(scanned, deleted, freed);
    }

    /**
     * 当前宽限期。
     *
     * @return 保留时长
     */
    public Duration minAge() {
        return minAge;
    }
}
