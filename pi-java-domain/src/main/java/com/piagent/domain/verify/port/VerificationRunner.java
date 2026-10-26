package com.piagent.domain.verify.port;

import com.piagent.domain.verify.model.VerificationOutcome;
import java.nio.file.Path;
import java.time.Duration;

/**
 * 验证命令执行端口。
 *
 * <p>命令来自用户配置而不是模型输入，因此不进入工具审批管线；执行器仍必须
 * 限制超时、终止整棵进程树并截断输出。</p>
 */
@FunctionalInterface
public interface VerificationRunner {

    /**
     * 在工作区执行验证命令。
     *
     * @param command 命令
     * @param workspace 工作区
     * @param timeout 超时
     * @param maxOutputChars 输出上限
     * @return 执行结果
     */
    VerificationOutcome run(String command, Path workspace, Duration timeout, int maxOutputChars);
}
