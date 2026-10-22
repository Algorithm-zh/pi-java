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
GET  /runs/{runId}                     run 快照
GET  /runs/{runId}/events              SSE 重放（Last-Event-ID）
GET  /models ...                       模型查询与管理
GET  /tools                            工具目录与策略效果
GET  /plugins                          插件安装记录
POST /plugins                          安装插件（201，仅落库）
POST /plugins/{id}/enable              加载并启用
POST /plugins/{id}/disable             停用并移出工具目录
GET  /system/info                      版本与默认工作目录
```

fork/branch/compact、审批、Skills 与 `/health` 随后续里程碑加入。
详见 [API 与 CLI](../docs/05-api-and-cli.md)。

## CLI

```text
pi chat / pi run / pi sessions / pi plugins / pi models / pi tools / pi serve
```

CLI 在 M9 实现，与 Web 共享同一个应用层用例与数据库。

## 当前状态

M0 建立模块；M2 完成 `/api/v1/models`（列表、详情、创建、更新、启停）与统一错误映射；
M3 完成 `GET /api/v1/tools`（可选 `cwd`，返回 schema 与策略效果）；M4 完成会话/运行
REST、SSE 流与重放、`/api/v1/system/info`；M5 增加插件管理端点与 404/409/422 映射。
共 21 个测试（插件 Controller 另由 app 模块的 3 个集成测试覆盖），行覆盖 35.6%。
M6 增加策略管理与审批 API，M9 实现完整 CLI。详见
[M5 记录](../docs/milestones/M5.md)。
