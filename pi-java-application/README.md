# pi-java-application

应用层。用例编排、Agent Loop、事务边界、读模型。

## 依赖

- `pi-java-domain`；
- Spring `spring-context` / `spring-tx`（仅注解与事务抽象）。

**禁止**：MyBatis、Jackson、Web、插件 API、infrastructure、trigger。

## 职责

- 一个用例一个类，输入使用自己的 `Command` / `Query` record，输出使用 `ReadModel`；
- `AgentLoopService` 拆分为 `RunLifecycle`、`TurnExecutor`、`ToolCallCoordinator`、
  `ContextAssemblyService`，避免巨型 Service；
- 事务只包住单次 entry 追加或状态更新；LLM、工具、审批等待期间不持有事务；
- 通过 domain 端口访问仓储与外部能力；不直接依赖具体实现。

## 计划包结构

```text
com.piagent.application
├── session      创建/分支/fork/查询会话
├── agent        run 生命周期、turn、工具协调、事件发布
├── model        模型档案管理
├── tool         工具目录查询
├── governance   策略与审批用例
├── context      压缩与 skills 刷新
├── plugin       插件安装/启停用例
└── readmodel    查询投影
```

## 事务与并发

- 同一 session 同时只允许一个活跃 run；
- 追加 entry 使用进程内短锁 + 数据库 `version` CAS；
- 取消令牌贯穿 LLM、工具、审批与子代理；
- 持久化失败直接终止 run，不做静默重试。

## 当前状态

M0：模块与依赖边界已建立。M1 实现会话用例，M4 实现 Agent Loop。
