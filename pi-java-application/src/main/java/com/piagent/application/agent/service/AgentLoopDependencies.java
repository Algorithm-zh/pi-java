package com.piagent.application.agent.service;

import com.piagent.application.git.GitCheckpointService;
import com.piagent.application.verify.VerificationService;
import java.util.Objects;

/**
 * Agent Loop 依赖集合。
 *
 * <p>循环服务需要的协作者已超过项目单构造器参数上限，集中为一个值对象；
 * 测试装配时也能直接看出循环依赖哪些能力。</p>
 *
 * @param models 运行期模型解析器
 * @param runtime 运行运行时
 * @param pipeline turn 流水线
 * @param dispatcher 调度器
 * @param compactionStep 上下文压缩步骤
 * @param gitCheckpoints Git 检查点服务
 * @param verification 自动验证服务
 */
public record AgentLoopDependencies(
        ActiveModelResolver models,
        RunRuntime runtime,
        TurnPipeline pipeline,
        RunDispatcher dispatcher,
        RunCompactionStep compactionStep,
        GitCheckpointService gitCheckpoints,
        VerificationService verification) {

    /**
     * 校验依赖。
     *
     * @param models 模型解析器
     * @param runtime 运行时
     * @param pipeline turn 流水线
     * @param dispatcher 调度器
     * @param compactionStep 压缩步骤
     * @param gitCheckpoints Git 检查点
     * @param verification 验证服务
     */
    public AgentLoopDependencies {
        Objects.requireNonNull(models, "models 不能为空");
        Objects.requireNonNull(runtime, "runtime 不能为空");
        Objects.requireNonNull(pipeline, "pipeline 不能为空");
        Objects.requireNonNull(dispatcher, "dispatcher 不能为空");
        Objects.requireNonNull(compactionStep, "compactionStep 不能为空");
        Objects.requireNonNull(gitCheckpoints, "gitCheckpoints 不能为空");
        Objects.requireNonNull(verification, "verification 不能为空");
    }
}
