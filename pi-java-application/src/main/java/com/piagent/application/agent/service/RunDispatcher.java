package com.piagent.application.agent.service;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Agent Loop 调度器：限制全局并发 run 数。
 *
 * <p>许可在提交任务前获取，任务结束时释放。并发数按 {@code pi.agent.max-concurrent-runs}
 * 配置；达到上限时立即拒绝而不是排队，避免用户看到“请求已接受但永远不开始”的状态。</p>
 */
@Component
public class RunDispatcher {

    private final Semaphore permits;
    private final ExecutorService executor;

    /**
     * 创建调度器。
     *
     * @param executor 运行循环执行器
     * @param maxConcurrent 最大并发 run 数
     */
    public RunDispatcher(@Qualifier("agentExecutor") ExecutorService executor,
            @Value("${pi.agent.max-concurrent-runs:4}") int maxConcurrent) {
        Guard.require(maxConcurrent >= 1, DomainErrorCode.INVALID_ARGUMENT,
                "max-concurrent-runs 必须大于 0");
        this.executor = executor;
        this.permits = new Semaphore(maxConcurrent);
    }

    /**
     * 尝试获取一个并发许可。
     *
     * @return true 表示获取成功
     */
    public boolean tryAcquire() {
        return permits.tryAcquire();
    }

    /**
     * 释放一个并发许可。
     */
    public void release() {
        permits.release();
    }

    /**
     * 提交运行任务；任务结束时自动释放许可。
     *
     * @param task 运行任务
     */
    public void dispatch(Runnable task) {
        try {
            executor.execute(() -> {
                try {
                    task.run();
                } finally {
                    permits.release();
                }
            });
        } catch (RuntimeException error) {
            permits.release();
            throw error;
        }
    }
}
