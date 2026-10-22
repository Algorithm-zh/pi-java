package com.piagent.application.testsupport;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 同步执行的测试执行器。
 *
 * <p>Agent Loop 测试用它把异步调度变为确定性的同步调用，避免等待与超时；
 * 取消场景通过脚本流内部的回调触发，不依赖线程交错。</p>
 */
public final class DirectExecutorService extends AbstractExecutorService {

    @Override
    public void execute(Runnable command) {
        command.run();
    }

    @Override
    public void shutdown() {
    }

    @Override
    public List<Runnable> shutdownNow() {
        return List.of();
    }

    @Override
    public boolean isShutdown() {
        return false;
    }

    @Override
    public boolean isTerminated() {
        return false;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
        return true;
    }
}
