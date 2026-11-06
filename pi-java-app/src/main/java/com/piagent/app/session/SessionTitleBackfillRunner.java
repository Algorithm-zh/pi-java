package com.piagent.app.session;

import com.piagent.application.session.service.SessionTitleBackfillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时把存量会话的占位标题替换为首条消息摘要。
 *
 * <p>只处理“新会话”这类默认标题，手动命名的会话不会被改动；回填失败只记录
 * 告警，不能阻塞应用启动。</p>
 */
@Component
public class SessionTitleBackfillRunner implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(SessionTitleBackfillRunner.class);

    private final SessionTitleBackfillService backfill;

    /**
     * 创建启动器。
     *
     * @param backfill 回填服务
     */
    public SessionTitleBackfillRunner(SessionTitleBackfillService backfill) {
        this.backfill = backfill;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int renamed = backfill.backfill();
            if (renamed > 0) {
                LOG.info("已根据首条消息为 {} 个会话补充标题", renamed);
            }
        } catch (RuntimeException error) {
            LOG.warn("回填会话标题失败，既有会话可继续使用", error);
        }
    }
}
