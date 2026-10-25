package com.piagent.app.session;

import com.piagent.application.agent.service.StaleRunRecoveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时复位进程上次退出时遗留的运行态会话。
 *
 * <p>否则数据库里残留的 RUNNING / WAITING_APPROVAL 会让会话在 Web 上既不能发送
 * 消息也等不到审批结果。恢复失败只记录告警，不能阻塞应用启动。</p>
 */
@Component
public class StaleRunStartupRunner implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(StaleRunStartupRunner.class);

    private final StaleRunRecoveryService recovery;

    /**
     * 创建启动器。
     *
     * @param recovery 恢复服务
     */
    public StaleRunStartupRunner(StaleRunRecoveryService recovery) {
        this.recovery = recovery;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int recovered = recovery.recover();
            if (recovered > 0) {
                LOG.info("已恢复 {} 个重启前中断的会话", recovered);
            }
        } catch (RuntimeException error) {
            LOG.warn("恢复中断会话失败，可继续使用但个别会话可能需要手动停止", error);
        }
    }
}
