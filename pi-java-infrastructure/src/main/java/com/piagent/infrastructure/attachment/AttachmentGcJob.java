package com.piagent.infrastructure.attachment;

import com.piagent.application.attachment.AttachmentReclaimResult;
import com.piagent.application.attachment.service.AttachmentMaintenanceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每日附件回收任务。
 *
 * <p>只清理超过宽限期且没有任何会话引用的图片；没有可删内容时不写日志，
 * 避免本地工具长期运行时刷屏。</p>
 */
@Component
public class AttachmentGcJob {

    private static final Logger LOG = LoggerFactory.getLogger(AttachmentGcJob.class);

    private final AttachmentMaintenanceService maintenance;

    /**
     * 创建任务。
     *
     * @param maintenance 回收服务
     */
    public AttachmentGcJob(AttachmentMaintenanceService maintenance) {
        this.maintenance = maintenance;
    }

    /**
     * 执行回收。
     */
    @Scheduled(cron = "${pi.attachments.gc-cron:0 40 3 * * *}")
    public void reclaim() {
        AttachmentReclaimResult result = maintenance.reclaim();
        if (result.deleted() > 0) {
            LOG.info("附件回收完成：扫描 {} 个，删除 {} 个，释放 {} 字节",
                    result.scanned(), result.deleted(), result.freedBytes());
        }
    }
}
