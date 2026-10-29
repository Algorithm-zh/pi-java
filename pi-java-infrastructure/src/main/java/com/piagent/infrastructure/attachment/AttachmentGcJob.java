package com.piagent.infrastructure.attachment;

import com.piagent.application.attachment.AttachmentReclaimResult;
import com.piagent.application.attachment.service.AttachmentMaintenanceService;
import com.piagent.application.identity.TenantTaskRunner;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每日附件回收任务。
 *
 * <p>只清理超过宽限期且没有任何会话引用的图片；没有可删内容时不写日志，
 * 避免本地工具长期运行时刷屏。回收逐个租户执行：附件目录与引用列表都按
 * 租户划分，定时任务没有请求上下文，不显式绑定就只会清理默认租户。</p>
 */
@Component
public class AttachmentGcJob {

    private static final Logger LOG = LoggerFactory.getLogger(AttachmentGcJob.class);

    private final AttachmentMaintenanceService maintenance;
    private final TenantTaskRunner tenants;

    /**
     * 创建任务。
     *
     * @param maintenance 回收服务
     * @param tenants 逐租户执行器
     */
    public AttachmentGcJob(AttachmentMaintenanceService maintenance, TenantTaskRunner tenants) {
        this.maintenance = maintenance;
        this.tenants = tenants;
    }

    /**
     * 执行回收。
     */
    @Scheduled(cron = "${pi.attachments.gc-cron:0 40 3 * * *}")
    public void reclaim() {
        List<AttachmentReclaimResult> results =
                tenants.forEachTenant(tenant -> maintenance.reclaim());
        int scanned = results.stream().mapToInt(AttachmentReclaimResult::scanned).sum();
        int deleted = results.stream().mapToInt(AttachmentReclaimResult::deleted).sum();
        long freed = results.stream().mapToLong(AttachmentReclaimResult::freedBytes).sum();
        if (deleted > 0) {
            LOG.info("附件回收完成：扫描 {} 个，删除 {} 个，释放 {} 字节", scanned, deleted, freed);
        }
    }
}
