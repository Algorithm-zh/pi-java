# 05 API 与 CLI

> 状态：M0 冻结。HTTP 前缀固定 `/api/v1`；CLI 命令名固定为 `pi`。
> Web 与 CLI 共享应用层用例，任何业务规则不得写在 Controller 或命令类中。

## 1. 通用约定

- 请求/响应：`application/json; charset=UTF-8`；SSE：`text/event-stream`；
- 时间：ISO-8601 UTC，毫秒精度，例如 `2026-10-21T06:00:00.123Z`；
- ID：ULID 字符串，26 位；不暴露自增主键（策略表除外）；
- 字段命名：camelCase；未知字段忽略；DTO 不含领域对象；
- 分页：`?limit=50&cursor=<opaque>`，v0.1 只对会话列表与 entry 列表提供；
- 鉴权：v0.1 **无鉴权**，默认只监听 `127.0.0.1`，禁止直接暴露公网；
- 幂等：`POST /sessions/{id}/abort`、审批决策、compact 对终态重复调用返回当前状态，不重复写 entry；
- 响应头 `X-Request-Id` 用于日志关联。

### 1.1 错误格式

```json
{
  "code": "SESSION_BUSY",
  "message": "该会话已有运行中的任务",
  "details": {"sessionId": "01J..."},
  "requestId": "req_01J..."
}
```

| HTTP | 典型 code | 场景 |
|---|---|---|
| 400 | `INVALID_ARGUMENT` | 参数缺失、格式错误 |
| 403 | `TOOL_DENIED` / `PATH_OUTSIDE_SANDBOX` | 策略拒绝、路径越界 |
| 404 | `SESSION_NOT_FOUND` / `APPROVAL_NOT_FOUND` | 资源不存在 |
| 409 | `SESSION_BUSY` / `INVALID_STATE` / `APPROVAL_ALREADY_DECIDED` | 状态冲突 |
| 429 | `RUN_CAPACITY_EXCEEDED` | 全局并发已满 |
| 500 | `INTERNAL_ERROR` / `PERSISTENCE_FAILED` | 未预期错误、落库失败 |

## 2. 会话 API

### 2.1 新建会话

```http
POST /api/v1/sessions
Content-Type: application/json

{
  "cwd": "D:/code/demo",
  "title": "重构登录模块",
  "modelProfileId": "deepseek-chat",
  "thinkingLevel": "MEDIUM",
  "origin": "USER"
}
```

响应 `201`：

```json
{
  "id": "01J...",
  "rootSessionId": "01J...",
  "parentSessionId": null,
  "origin": "USER",
  "title": "重构登录模块",
  "cwd": "D:/code/demo",
  "modelProfileId": "deepseek-chat",
  "thinkingLevel": "MEDIUM",
  "status": "IDLE",
  "leafEntryId": null,
  "version": 0,
  "createdAt": "2026-10-21T06:00:00.000Z",
  "updatedAt": "2026-10-21T06:00:00.000Z"
}
```

约束：`cwd` 必须存在且为目录；服务端规范化后作为沙箱根；`title` 为空时自动生成
（取首条用户消息前 40 字或 `新会话`）。

### 2.2 列表与详情

```http
GET /api/v1/sessions?cwd=D:/code/demo&limit=20&cursor=
GET /api/v1/sessions/{sessionId}
DELETE /api/v1/sessions/{sessionId}
```

- 列表按 `updated_at DESC`，不返回 entry 正文；
- 详情包含状态、当前 leaf、分支摘要（直接子会话数量）；
- 删除为级联删除 entry 与审批投影；v0.1 不做软删除。

### 2.3 分支与树

```http
GET /api/v1/sessions/{sessionId}/entries?leafId={entryId}&view=branch
GET /api/v1/sessions/{sessionId}/entries?view=tree
```

`view=branch`（默认）：从指定 leaf（缺省为会话当前 leaf）回溯，返回该分支的 entry 列表：

```json
{
  "sessionId": "01J...",
  "leafId": "01J...",
  "entries": [
    {
      "id": "01J...",
      "seq": 1,
      "parentId": null,
      "type": "MESSAGE",
      "payloadVersion": 1,
      "tokenEstimate": 12,
      "createdAt": "2026-10-21T06:00:01.000Z",
      "payload": {"role": "user", "content": [{"type": "text", "text": "你好"}]}
    }
  ]
}
```

`view=tree`：返回节点摘要（id、parentId、seq、type、createdAt、label、子节点数量），
不返回完整 payload，供 UI 绘制会话树。

### 2.4 Fork

```http
POST /api/v1/sessions/{sessionId}/fork?entryId={entryId}
```

- 不传 `entryId` 时从当前 leaf fork；
- 新会话 `origin=FORK`，`parent_session_id=源会话`，`root_session_id=源根`，`depth+1`；
- 复制源分支到 fork 点的 entry 引用语义由应用层处理：v0.1 只复制**会话元数据**，
  新会话从空日志开始，并在首条 `BRANCH_SUMMARY` entry 中写入“从哪个节点 fork”；
  这样不复制历史，也不会让 fork 后的 token 成本失控；
- 返回新会话详情。

> 说明：与 pi 的“原地切分支”不同，pi-java 的 fork 创建**新会话**；
> 原地移动 leaf 通过 `POST /sessions/{id}/branch` 完成。

### 2.5 切换分支

```http
POST /api/v1/sessions/{sessionId}/branch
Content-Type: application/json

{"entryId": "01J...", "summarize": true}
```

- `entryId` 必须属于该会话且不是 toolResult；
- `summarize=true` 时先为被离开的分支生成 `BRANCH_SUMMARY` entry；
- 只移动 `leaf_entry_id`，不修改历史 entry；
- 有活跃 run 时返回 409。

### 2.6 发送消息（SSE）

```http
POST /api/v1/sessions/{sessionId}/messages/stream
Content-Type: application/json
Accept: text/event-stream

{"content": "读取 pom.xml 并解释依赖"}
```

响应 `200 text/event-stream`，事件格式：

```text
id: 01J-run:1
event: run.started
data: {"seq":1,"runId":"01J-run","sessionId":"01J...","type":"run.started","at":"2026-10-21T06:00:00.000Z","data":{"model":"deepseek-chat"}}

id: 01J-run:2
event: message.delta
data: {"seq":2,...,"data":{"delta":"我先"}}

: heartbeat
```

- 事件类型与 data 字段见 [04 运行时与事件](04-runtime-and-events.md)；
- 客户端可用 `Last-Event-ID` 请求头重连；`POST` 重连时用同一请求体与
  `GET /runs/{runId}/events` 组合恢复；
- 该请求在 run 结束后才返回，超时由 `SseEmitter(timeout=0)` 保持；
- 同一会话已有活跃 run 时返回 409。

### 2.7 断线重连

```http
GET /api/v1/runs/{runId}/events
Last-Event-ID: 01J-run:17
Accept: text/event-stream
```

- 命中缓冲：从 18 开始回放，再持续推送新事件；
- 超出缓冲：先发 `replay.gap`，客户端调用 entry 接口重拉完整分支；
- run 已结束且缓冲仍在：回放后发送终态并关闭；
- run 不存在或缓冲已清理：404，客户端以 entry 数据为准。

### 2.8 取消与压缩

```http
POST /api/v1/sessions/{sessionId}/abort
POST /api/v1/sessions/{sessionId}/compact
Content-Type: application/json

{"instructions": "重点保留数据库设计决策"}
```

- abort：无活跃 run 时幂等返回当前状态；有则触发取消令牌；
- compact：手动压缩，可在 IDLE 时执行；有活跃 run 时排队到当前 turn 结束后执行；
- 响应返回 `runId`、`accepted` 与当前状态。

## 3. 审批 API

```http
GET /api/v1/approvals?status=PENDING&sessionId=01J...
POST /api/v1/approvals/{approvalId}/decision
Content-Type: application/json

{"decision": "APPROVED", "reason": "允许修改 README"}
```

审批 DTO：

```json
{
  "id": "01J...",
  "sessionId": "01J...",
  "runId": "01J...",
  "toolCallId": "call_2",
  "toolName": "write",
  "riskLevel": "HIGH",
  "argsDigest": "sha256...",
  "argsRedacted": {"path": "README.md", "content": "..."},
  "status": "PENDING",
  "expiresAt": "2026-10-21T06:30:00.000Z",
  "createdAt": "2026-10-21T06:00:00.000Z"
}
```

- `decision` 只能是 `APPROVED` / `DENIED`；
- 非 PENDING 返回 409 `APPROVAL_ALREADY_DECIDED`；
- 过期返回 409 `APPROVAL_EXPIRED`，并已由清理任务置为 EXPIRED；
- 决策成功后追加 `APPROVAL` entry，并通过 SSE 推送 `approval.resolved`。

## 4. 模型 API

```http
GET  /api/v1/models
GET  /api/v1/models/{id}
POST /api/v1/models
PUT  /api/v1/models/{id}
POST /api/v1/models/{id}/enable
POST /api/v1/models/{id}/disable
```

创建请求（更新使用同一 endpoint 结构，路径中的 `{id}` 为准）：

```json
{
  "id": "deepseek-chat",
  "displayName": "DeepSeek Chat",
  "endpoint": {
    "provider": "OPENAI_COMPATIBLE",
    "baseUrl": "https://api.deepseek.com",
    "apiKeyEnv": "DEEPSEEK_API_KEY",
    "modelName": "deepseek-chat",
    "contextWindow": 65536,
    "maxOutputTokens": 8192,
    "capabilities": {"streaming": true, "toolCalls": true, "thinking": false}
  }
}
```

响应在请求字段之外增加 `enabled` 与 `version`，**任何响应都不返回密钥值**：

```json
{
  "id": "deepseek-chat",
  "displayName": "DeepSeek Chat",
  "endpoint": {"provider": "OPENAI_COMPATIBLE", "...": "..."},
  "enabled": true,
  "version": 1
}
```

约束与错误：

- `apiKeyEnv` 只接受环境变量名；服务端校验 `[A-Z][A-Z0-9_]{0,127}`，不接收密钥字面量；
- `contextWindow`、`maxOutputTokens` 必须为正且输出不超过上下文；`baseUrl` 必须为
  http/https（`FAKE` 除外）；
- `POST` 遇到重复 id 返回 409 `MODEL_ALREADY_EXISTS`；`PUT` 使用服务端读到的当前版本
  做 CAS，并发冲突返回 409 `CONCURRENT_MODIFICATION`；
- 未知 provider 返回 400 `INVALID_ARGUMENT`；空请求体、非法 JSON 返回 400；
- 找不到档案返回 404 `MODEL_NOT_FOUND`；停用档案返回 409 `MODEL_DISABLED`（当用例要求
  启用档案时）；
- `enable/disable` 幂等：重复调用仍返回当前档案与版本。

## 5. 工具与策略 API

```http
GET /api/v1/tools?cwd=D:/code/demo
GET /api/v1/tool-policies?cwd=D:/code/demo
PUT /api/v1/tool-policies?cwd=D:/code/demo
```

策略 PUT 请求：

```json
{
  "rules": [
    {"scope": "WORKSPACE", "toolName": "bash", "effect": "DENY", "riskLevel": "CRITICAL"},
    {"scope": "WORKSPACE", "toolName": "write", "effect": "ASK", "approvalMode": "ONCE"}
  ]
}
```

- `cwd` 用于计算 `workspace_key`；省略时使用用户级作用域；
- `GET /tools` 返回工具名、描述、风险、来源（BUILTIN/PLUGIN）、只读标记，不返回完整 schema；
- 未知工具不能创建 ALLOW 规则（防止插件卸载后残留放行）。

## 6. 插件 API

```http
GET  /api/v1/plugins
POST /api/v1/plugins
POST /api/v1/plugins/{id}/enable
POST /api/v1/plugins/{id}/disable
```

安装请求（multipart 或本地路径）：

```json
{
  "jarPath": "D:/plugins/hello-plugin-1.0.0.jar",
  "config": {}
}
```

- 服务端计算 SHA-256、读取 manifest、校验 API 主版本与工具重名；
- 安装后默认 `INSTALLED`，显式 enable 才加载；
- disable 先置 `DISABLED`，等待活跃调用归零后关闭 ClassLoader；
- 运行中的 run 继续使用旧工具快照，不因卸载而失败。

## 7. Skills 与健康检查

```http
GET  /api/v1/skills
POST /api/v1/skills/refresh
GET  /api/v1/health
```

`/skills` 返回 name、description、path、source（GLOBAL/PROJECT/CONFIG）；
`refresh` 清空并重建 Skill 目录缓存；`/health` 返回：

```json
{
  "status": "UP",
  "database": "UP",
  "migration": "UP",
  "plugins": {"active": 2, "failed": 0},
  "activeRuns": 1
}
```

## 8. CLI

启动方式：

```bash
java -jar pi-java-app.jar --spring.profiles.active=demo
# 或安装脚本后
pi serve --port 8080
```

### 8.1 命令总览

```text
pi chat   [--cwd PATH] [--model ID] [--session ID] [--profile demo|mysql]
pi run    "任务描述" [--cwd PATH] [--model ID] [--json] [--session ID]
pi sessions list|show|resume|fork [--cwd PATH]
pi plugins list|install|enable|disable
pi models list|add
pi tools list [--cwd PATH]
pi serve  [--port 8080] [--profile demo|mysql]
```

### 8.2 `pi chat`

交互式 REPL：

- 输入普通文本 → 创建/继续会话并发起 run；
- `/exit`、`/quit` 退出；
- `/abort` 取消当前 run；
- `/compact [说明]` 手动压缩；
- `/skill:name [args]` 强制加载 skill 正文并发送；
- `/tree` 显示当前分支节点摘要；
- `/model <id>` 切换模型（写 `MODEL_CHANGE` entry）；
- `/help` 命令帮助；
- Ctrl+C 第一次取消当前 run，第二次退出。

渲染：流式文本、工具调用卡片（名称/参数摘要/结果摘要）、审批提示（y/n/a，
a = 本会话后续允许）、token 使用量、上下文占用百分比。

### 8.3 `pi run`

非交互单次执行：

```bash
pi run "读取 pom.xml 并总结依赖" --cwd D:/code/demo --json
```

- 默认流式打印文本与工具摘要；
- `--json` 输出结构化结果：`runId/sessionId/stopReason/usage/turns/toolCalls/finalText`；
- 退出码：`0` 成功，`1` 运行失败，`2` 参数错误，`3` 审批被拒绝/超时，`4` 取消。

### 8.4 其他命令

```bash
pi sessions list --cwd D:/code/demo --limit 20
pi sessions show 01J...
pi sessions resume 01J...          # 进入 chat，继续该会话
pi sessions fork 01J... --entry 01J...
pi plugins install ./hello-plugin.jar
pi plugins enable hello-plugin
pi plugins disable hello-plugin
pi models list
pi models add --id deepseek-chat --provider OPENAI_COMPATIBLE \
  --base-url https://api.deepseek.com/v1 --api-key-env DEEPSEEK_API_KEY \
  --model-name deepseek-chat --context-window 65536
pi tools list --cwd D:/code/demo
```

CLI 与 Web 使用同一个数据库与同一套用例；CLI 不直接操作 Mapper。

## 9. Web UI

静态资源位于 `pi-java-app/src/main/resources/static/`，原生 HTML/CSS/JS，无构建步骤。

页面结构：

```text
┌───────────────┬─────────────────────────────────────┬──────────────────┐
│ 会话侧栏       │ 消息流                               │ 右侧上下文面板     │
│ + 新建         │ - 用户/助手气泡                       │ - 模型/思考级别    │
│ 会话列表       │ - 工具卡片（可展开参数与输出）          │ - 上下文占用条     │
│ 分支/子会话     │ - 审批弹窗（APPROVE/DENY）            │ - token 统计       │
│ 设置入口       │ - 流式光标、取消按钮                   │ - 工具策略提示     │
└───────────────┴─────────────────────────────────────┴──────────────────┘
```

必须覆盖的交互：

1. 新建会话 → 发送消息 → 流式增量 → 完成；
2. 工具卡片展开/折叠，显示参数摘要、输出摘要、耗时；
3. 审批弹窗展示脱敏参数、风险、过期时间；允许/拒绝后继续；
4. 取消运行，显示 ABORTED；
5. 会话树查看与切换分支；
6. 模型/插件/Skills 设置页；
7. SSE 断开后自动重连或提示重拉。

## 10. 版本与兼容

- API 版本在路径中；破坏性变更升 `/api/v2`；
- 响应只增字段不删字段；客户端忽略未知字段；
- 错误 code 稳定，message 可调整；
- CLI 参数只增不删；废弃参数先告警一个 minor 版本；
- OpenAPI 描述 v0.1 暂不生成，接口以本文与 DTO 测试为准。

## 11. 相关文档

- [运行时与事件](04-runtime-and-events.md)
- [插件 SDK](06-plugin-sdk.md)
