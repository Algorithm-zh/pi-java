# pi-java-trigger

触发层：HTTP/SSE Controller 与 picocli CLI。只依赖应用层与 Web/CLI 库。

**禁止**：依赖 infrastructure、Mapper、具体仓储实现。

## 职责

- HTTP：参数绑定、DTO 映射、状态码、SSE 序列化；
- CLI：参数解析、交互式渲染、退出码；
- 调用 application 用例，不复制业务规则；
- 错误统一映射为 `{code, message, details, requestId}`。

## 端点

前缀 `/api/v1`：sessions、entries、fork、branch、messages/stream、abort、compact、
approvals、models、tools、tool-policies、plugins、skills、health。

详见 [API 与 CLI](../docs/05-api-and-cli.md)。

## CLI

```text
pi chat / pi run / pi sessions / pi plugins / pi models / pi tools / pi serve
```

CLI 与 Web 共享同一个应用层用例与数据库。

## 当前状态

M0 建立模块；M2 完成 `/api/v1/models`（列表、详情、创建、更新、启停）与统一错误映射，
8 个 standalone MockMvc 测试。M4 实现会话 SSE 与只读 Web demo，M9 实现完整 CLI。
