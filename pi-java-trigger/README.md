# pi-java-trigger

触发层：HTTP/SSE Controller 与 picocli CLI。只依赖应用层与 Web/CLI 库。

**禁止**：依赖 infrastructure、Mapper、具体仓储实现。

## 职责

- HTTP：参数绑定、DTO 映射、状态码、SSE 序列化；
- CLI：参数解析、交互式渲染、退出码；
- 调用 application 用例，不复制业务规则；
- 错误统一映射为 `{code, message}`；未映射异常交给框架返回 500。

## 端点

前缀 `/api/v1`，M5 已实现：

```text
POST /sessions                         创建会话
GET  /sessions                         列表
GET  /sessions/{id}                    详情
GET  /sessions/{id}/entries            分支 entry
GET  /sessions/{id}/run                活跃 run（204 表示空闲）
POST /sessions/{id}/messages/stream    发起 run + SSE
POST /sessions/{id}/abort              取消 run（202）
POST /sessions/{id}/compact            手动压缩上下文
GET  /runs/{runId}                     run 快照
GET  /runs/{runId}/events              SSE 重放（Last-Event-ID）
GET  /models ...                       模型查询与管理
GET  /tools                            工具目录与策略效果
GET  /tool-policies                    工作区 + USER 策略列表
PUT  /tool-policies                    创建或更新策略（upsert）
GET  /approvals?sessionId=&status=     审批列表（参数脱敏 + digest）
GET  /approvals/{id}                   审批详情
POST /approvals/{id}/decision          批准/拒绝（409 表示非 PENDING）
GET  /plugins                          插件安装记录
POST /plugins                          安装插件（201，仅落库）
POST /plugins/{id}/enable              加载并启用
POST /plugins/{id}/disable             停用并移出工具目录
GET  /skills?refresh=                  技能目录（仅名称与描述）
GET  /skills/{name}                    单个技能
GET  /skills/{name}/content            技能正文（去 frontmatter）
GET  /system/info                      版本与默认工作目录
```

fork/branch 与 `/health` 随后续里程碑加入。详见 [API 与 CLI](../docs/05-api-and-cli.md)。

## CLI（M11）

```text
pi run      [MESSAGE] [--cwd PATH] [--model ID] [--thinking LEVEL] [--session ID]
            [--title TITLE] [--json] [--timeout SECONDS] [--approve]
pi chat     [--cwd PATH] [--model ID] [--thinking LEVEL] [--session ID] [--title TITLE]
            [--plain]
pi sessions list|show
pi models   list
pi tools    list
pi serve    [--port PORT] [--profile demo|mysql]
```

- 命令 Bean 使用 prototype 作用域，保证多次 `CommandLine.execute` 之间不残留上次参数；
- 输入流通过 `CliTerminal` 的 ThreadLocal 绑定注入（picocli 4.7 只管理 out/err）；
- run 事件由主线程轮询队列消费，审批提问与 `/abort` 读取也只在主线程进行；
- `chat` 在真实终端走 JLine 交互界面（`CliTui`：方框输入区、Tab 补全、彩色流式、
  信号式 Ctrl+C），管道/重定向/`--plain` 退回 `LineChat` 逐行模式；
- 输出编码跟随原生控制台，Git Bash 等 UTF-8 环境自动切换，可用 `PI_CLI_ENCODING` 覆盖；
- 退出码：`0` 成功、`1` 失败/超时、`2` 参数错误、`3` 审批拒绝、`4` 取消；
- CLI 与 Web 共享同一个 Spring 容器、数据库与全部应用用例，不直接操作 Mapper。

详见 [API 与 CLI](../docs/05-api-and-cli.md) §8。

## 当前状态

M0 建立模块；M2 完成 `/api/v1/models`（列表、详情、创建、更新、启停）与统一错误映射；
M3 完成 `GET /api/v1/tools`（可选 `cwd`，返回 schema 与策略效果）；M4 完成会话/运行
REST、SSE 流与重放、`/api/v1/system/info`；M5 增加插件管理端点与 404/409/422 映射；
M6 增加审批（列表/详情/决策）与策略管理（GET/PUT）端点，并把
`APPROVAL_NOT_FOUND` 映射为 404、`APPROVAL_INVALID_STATE` 映射为 409；M7 增加
`POST /sessions/{id}/compact` 与 `GET /skills`、`/skills/{name}`、`/skills/{name}/content`，
新增 `SKILL_NOT_FOUND → 404` 映射。M11 实现 CLI 命令树、流式渲染、审批交互与退出码。
详见 [M11 记录](../docs/milestones/M11.md)。
