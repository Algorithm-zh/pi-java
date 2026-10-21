# pi-java-domain

纯领域层。**只依赖 JDK**，不出现 Spring、Jackson、MyBatis、Web、插件 API 类型。

## 职责

- 7 个限界上下文的聚合、实体、值对象、领域服务与仓储/端口接口；
- 表达业务不变量与状态机，不关心持久化、协议与框架；
- 通过 `java.time.Clock`、`IdGenerator` 注入时间与 ID，保证可测试。

## 包结构

```text
com.piagent.domain
├── shared        ULID、错误码、领域异常、Guard
├── session       会话树、entry、消息 sealed、投影、分支策略
├── agent         AgentRun、turn/step、预算、停止原因、子代理策略
├── model         ModelProfile、能力、选择策略、LlmGateway 端口
├── tool          ToolDescriptor/Catalog、调用与结果
├── governance    工具策略、审批、路径沙箱
├── context       token 估算、压缩计划、Skills 目录
└── extension     插件安装、兼容性、激活策略
```

每个上下文内部按 `model / service / port` 组织；单个包超过 15 个类时拆子包。

## 禁止事项

- 引入框架注解或序列化库；
- 在领域层访问数据库、文件系统、网络、进程；
- 跨上下文调用聚合行为（只允许身份引用或只读值对象）；
- 把 HTTP/SSE/CLI 的 DTO 放进领域层。

## 测试

- JUnit 5 + AssertJ，不启动 Spring；
- 投影器、分支策略、压缩切点、审批状态机、路径判定必须有不变量测试；
- 覆盖率门槛从 M1 起 60%，随里程碑只升不降。

## 当前状态

M1 已完成：`shared`（ULID/错误码/异常/Guard）与 `session`（聚合、entry、消息 sealed、
10 种 payload、投影器、分支策略、仓储端口）已落地，domain 单测 30 个、行覆盖 89.4%。
其余上下文的类型从 M2 起按里程碑加入。详见
[领域模型](../docs/02-domain-model.md) 与 [M1 记录](../docs/milestones/M1.md)。
