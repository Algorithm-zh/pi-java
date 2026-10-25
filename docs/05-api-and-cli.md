# 05 API 与 CLI

> 状态：M9 已实现会话/运行 REST、SSE、模型与工具查询、审批与策略、插件管理、
> Skills、手动压缩、会话设置（重命名/切换模型/归档）与上下文状态；Web UI 已接入
> 设置中心；M11 已实现第 8 节的 CLI 子集（`run`/`chat`/只读命令/`serve`）。
> HTTP 前缀固定 `/api/v1`；CLI 命令名固定为 `pi`。
> Web 与 CLI 共享应用层用例，任何业务规则不得写在 Controller 或命令类中。

## 1. 通用约定

- 请求/响应：`application/json; charset=UTF-8`；SSE：`text/event-stream`；
- 时间：ISO-8601 UTC 字符串（`Instant.toString()`），不做本地时区转换；
- ID：ULID 字符串，26 位；不暴露自增主键（策略表除外）；
- 字段命名：camelCase；未知字段忽略；DTO 不含领域对象；
- 分页：M4 只有 `limit`，没有 cursor；会话列表默认 50 条；
- 鉴权：v0.1 **无鉴权**，默认只监听 `127.0.0.1`，禁止直接暴露公网；
- 幂等：`POST /sessions/{id}/abort` 对没有活跃 run 的会话返回 404，不重复写 entry；
- 请求 ID 与日志关联字段尚未实现，M9 打磨阶段补充。

### 1.1 错误格式

当前的错误响应只有稳定错误码与可读消息：

```json
{
  "code": "AGENT_BUSY",
  "message": "并发运行数已达上限，请稍后重试"
}
```

| HTTP | 实际 code | 场景 |
|---|---|---|
| 400 | `INVALID_ARGUMENT` | 参数缺失、ULID/路径非法、请求体不是合法 JSON |
| 404 | `SESSION_NOT_FOUND` / `RUN_NOT_FOUND` / `MODEL_NOT_FOUND` / `ENTRY_NOT_FOUND` / `PLUGIN_NOT_FOUND` | 资源不存在 |
| 409 | `RUN_ALREADY_ACTIVE` / `CONCURRENT_MODIFICATION` / `MODEL_ALREADY_EXISTS` / `MODEL_DISABLED` / `INVALID_STATE_TRANSITION` / `SESSION_ARCHIVED` / `PLUGIN_ALREADY_EXISTS` / `PLUGIN_INVALID_STATE` / `PLUGIN_TOOL_CONFLICT` | 状态或并发冲突 |
| 422 | `PLUGIN_INCOMPATIBLE` / `PLUGIN_LOAD_FAILED` | 插件 jar/API 不兼容或加载失败 |
| 429 | `AGENT_BUSY` | 全局并发 run 已满 |
| 500 | 框架默认错误体 | 未映射异常；不伪装成领域错误 |

工具被策略拒绝、工具执行失败不产生 HTTP 错误，而是 `isError=true` 的 toolResult
与 `tool.completed` 事件，由模型自行处理。

## 2. 会话 API

### 2.1 新建会话

```http
POST /api/v1/sessions
Content-Type: application/json

{
  "title": "重构登录模块",
  "cwd": "D:/code/demo",
  "modelProfileId": "fake-demo",
  "thinkingLevel": "OFF"
}
```

- `thinkingLevel` 可省略，默认 `OFF`；取值 `OFF / LOW / MEDIUM / HIGH`；
  实际能力在每次 run 启动时校验，模型不支持思考却配置了非 `OFF` 级别会返回
  400 `MODEL_CAPABILITY_MISSING`；
- `cwd` 必须存在且为目录，服务端规范化后作为沙箱根；
- 根会话的 `parentSessionId` 为 null、`rootSessionId` 等于自身、`depth=0`、`origin=USER`。

响应 `201`（字段即 `SessionResponse`）：

```json
{
  "id": "01J...",
  "parentSessionId": null,
  "rootSessionId": "01J...",
  "title": "重构登录模块",
  "cwd": "D:\\code\\demo",
  "modelProfileId": "fake-demo",
  "thinkingLevel": "OFF",
  "status": "IDLE",
  "leafEntryId": null,
  "depth": 0,
  "origin": "USER",
  "version": 0
}
```

### 2.2 列表与详情

```http
GET /api/v1/sessions?cwd=D:/code/demo&limit=20
GET /api/v1/sessions/{sessionId}
```

- 列表按 `updated_at DESC`，`cwd` 可省略，`limit` 默认 50；
- 列表与详情使用同一个 `SessionResponse`，不返回 entry 正文；
- 删除接口见 2.9；分支摘要尚未实现。

### 2.3 分支 entry

```http
GET /api/v1/sessions/{sessionId}/entries?leafId={entryId}
```

省略 `leafId` 时使用会话当前 leaf；响应是**裸数组**，按 `seq` 升序。每个 entry 使用
稳定 envelope，前端不需要理解领域 payload 的 Java 类型：

```json
[
  {
    "id": "01J...",
    "parentId": null,
    "seq": 1,
    "type": "MESSAGE",
    "createdAt": "2026-10-21T06:00:01Z",
    "body": {
      "role": "user",
      "content": [{"type": "text", "text": "你好"}],
      "toolCallId": null,
      "toolName": null,
      "isError": null,
      "extra": {}
    }
  }
]
```

`body.role` 为 `user / assistant / tool / system`；`body.extra` 承载压缩、运行摘要、
审批、模型切换等结构化信息。`view=tree` 尚未实现。

### 2.4 Fork（应用用例已实现，HTTP 未暴露）

> 领域与应用层已有 `SessionApplicationService.fork`，M4 尚未提供 HTTP 端点；
> 以下为后续契约草案。

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

### 2.5 切换分支（应用用例已实现，HTTP 未暴露）

> `SessionApplicationService.moveBranch` 已存在，M4 尚未提供 HTTP 端点。

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
Accept: application/json, text/event-stream

{"text": "读取 pom.xml 并解释依赖"}
```

响应 `200 text/event-stream`。服务端先同步完成“追加用户消息 + 预留 run”，再返回
emitter；run 在虚拟线程执行，响应体随事件持续输出，终态后关闭：

```text
id: 01J-run:1
event: run.started
data: {"seq":1,"runId":"01J-run","sessionId":"01J...","type":"run.started","at":"2026-10-21T06:00:00Z","data":{"runId":"01J-run","modelProfileId":"fake-demo","modelName":"fake-demo","maxTurns":20,"startedAt":"..."}}

id: 01J-run:2
event: message.delta
data: {"seq":2,...,"type":"message.delta","data":{"kind":"text","text":"我先"}}
```

- 事件类型与 data 字段见 [04 运行时与事件](04-runtime-and-events.md)；
- 同一会话已有活跃 run 返回 409 `RUN_ALREADY_ACTIVE`；全局并发已满返回 429 `AGENT_BUSY`；
- `Last-Event-ID` 只对 `GET /runs/{runId}/events` 生效；POST 不能重放已创建的消息，
  断线后用 runId 走重连接口。

> **错误响应协商**：run 启动前的领域错误（如模型能力不匹配、会话已归档）会在
> emitter 建立之前以 4xx JSON 返回。客户端应同时声明
> `Accept: application/json, text/event-stream`；若只接受 `text/event-stream`，
> 框架无法把错误序列化为 JSON，会退化成 406/500。Web UI 的
> `api.js` 已按此约定发送请求头。

### 2.7 活跃 run 与断线重连

```http
GET /api/v1/sessions/{sessionId}/run
GET /api/v1/runs/{runId}
GET /api/v1/runs/{runId}/events
Last-Event-ID: 01J-run:17
Accept: text/event-stream
```

- `GET /sessions/{id}/run`：有活跃 run 返回 `{runId, sessionId}`，否则 `204`；
  页面刷新后用它在“新建流”和“重连流”之间选择；
- `GET /runs/{runId}`：返回 run 快照 `runId/sessionId/status/stopReason/turns/toolCalls/usage/startedAt/finishedAt/durationMs/errorMessage`；
- `Last-Event-ID` 支持 `runId:seq` 与纯数字；空值从头回放；
- 命中缓冲：回放 `seq+1` 起的事件，再持续推送；已结束且缓冲仍在：回放后关闭；
- 起点已淘汰：先收到 `replay.gap`，客户端调用 entry 接口重拉分支；
- run 不存在或事件通道已清理：404 `RUN_NOT_FOUND`。

### 2.8 取消

```http
POST /api/v1/sessions/{sessionId}/abort
```

响应 `202` 与 `{runId, sessionId}`；无活跃 run 返回 404 `RUN_NOT_FOUND`。
取消是协作式的：当前 LLM 流被关闭，已输出文本保留，随后写
`RUN_SUMMARY(stopReason=CANCELLED)`。

> 手动压缩见 7.3；上下文用量见 2.9。

### 2.8.1 进程重启后的自愈

run 只存在于进程内存。服务启动时，`StaleRunStartupRunner` 会遍历遗留的
`RUNNING` / `WAITING_APPROVAL` 会话以及最近会话：

- 取消遗留 `PENDING` 审批，`decidedBy=system`、`reason=server-restart`；
- 对分支上悬空的工具调用补写 `isError=true` 的结果
  （`details.interrupted=true`），否则后续消息会被配对校验拒绝；
- 把运行态会话复位为 `IDLE`，前端再次打开时通过 `GET /sessions/{id}/run`
  拿到 `204` 后按数据库状态刷新。

旧版本只复位状态、不闭合调用时，用户可能已经在悬空调用之后追加了失败消息。
这类会话会以调用所在的 assistant entry 为锚点追写结果并把 leaf 回挂到结果上，
跳过后续失败消息；原始 entry 仍保留在库中，但不再属于当前分支。

### 2.9 会话设置与上下文状态（M9）

```http
PATCH  /api/v1/sessions/{id}          # 重命名
DELETE /api/v1/sessions/{id}          # 删除
PUT    /api/v1/sessions/{id}/model    # 切换模型与思考级别
POST   /api/v1/sessions/{id}/archive  # 归档
GET    /api/v1/sessions/{id}/context  # 上下文用量估算
```

重命名请求：

```json
{ "title": "重构登录模块" }
```

切换模型请求：

```json
{ "modelProfileId": "deepseek-chat", "thinkingLevel": "MEDIUM" }
```

- `thinkingLevel` 可省略，省略时保留当前级别；
- 目标档案必须存在且启用，否则 404 `MODEL_NOT_FOUND` / 409 `MODEL_DISABLED`；
- 目标模型不支持思考时，非 `OFF` 级别返回 400 `MODEL_CAPABILITY_MISSING`；
  省略级别但沿用旧非 `OFF` 级别时同样拒绝，避免保存出无法运行的配置；
- 只有 `IDLE` 会话可以切换模型；运行中或等待审批返回 409 `INVALID_STATE_TRANSITION`；
- 切换只影响之后的 run，历史 assistant 消息保留各自实际使用的模型名。

归档：

- 只有 `IDLE` 会话可以归档，否则 409 `INVALID_STATE_TRANSITION`；
- 归档后 `status=ARCHIVED`，发送消息、压缩与再次切换模型都会被拒绝；
- 重命名不限制状态，归档会话仍可改名，方便在侧栏中辨认；
- 归档不删除任何 entry，会话仍可只读查看历史。

删除：

- 返回 `204`，会话与其 entry、审批记录由外键级联删除，不可恢复；
- 只有 `IDLE` 或 `ARCHIVED` 会话可以删除；运行中或等待审批返回 409
  `INVALID_STATE_TRANSITION`，需先停止；
- Web 侧栏通过会话行的 `⋯` 菜单调用重命名与删除，删除前二次确认。

上下文状态响应：

```json
{
  "tokens": 19539,
  "contextWindow": 64000,
  "availableTokens": 55808,
  "maxOutputTokens": 8192,
  "modelProfileId": "deepseek-chat",
  "modelName": "deepseek-chat",
  "provider": "OPENAI_COMPATIBLE"
}
```

`tokens` 是按当前分支估算的输入用量，不包含预留输出；`availableTokens =
contextWindow - maxOutputTokens`。该接口只读，不写 entry，也不触发压缩。

## 3. 审批 API（M6）

```http
GET  /api/v1/approvals?sessionId=01J...&status=PENDING
GET  /api/v1/approvals/{approvalId}
POST /api/v1/approvals/{approvalId}/decision
Content-Type: application/json

{"decision": "APPROVE", "reason": "允许修改 README"}
```

`sessionId` 必填，`status` 可选（缺省返回全部状态）。审批 DTO：

```json
{
  "approvalId": "01J...",
  "sessionId": "01J...",
  "runId": "01J...",
  "toolCallId": "call_2",
  "toolName": "write",
  "riskLevel": "HIGH",
  "argumentsDigest": "64 位小写十六进制",
  "arguments": {"path": "README.md", "content": "..."},
  "status": "PENDING",
  "expiresAt": "2026-10-21T06:30:00Z",
  "createdAt": "2026-10-21T06:00:00Z"
}
```

- `arguments` 是脱敏副本（敏感键值替换为 `***`）；`argumentsDigest` 基于原始参数，
  只出现在 HTTP 详情中，SSE 事件不包含它；
- `decision` 只能是 `APPROVE` / `DENY`（大小写不敏感）；决策者由服务端固定为
  `local-user`，请求体不能指定 `decidedBy`；
- 决策成功返回终态 DTO，增加 `decidedBy`、`decidedAt` 与可选 `reason`；
- 非 PENDING 或并发 CAS 失败返回 409 `APPROVAL_INVALID_STATE`；
  审批已过期时先迁移为 EXPIRED，再返回 409；
- 决策成功后追加 `APPROVAL` entry，并在事务提交后推送 `approval.resolved`；
- `approval.requested` / `approval.resolved` 是运行 SSE 事件，不单独提供订阅端点。

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

响应在请求字段之外增加 `enabled`、`apiKeyConfigured` 与 `version`，
**任何响应都不返回密钥值**：

```json
{
  "id": "deepseek-chat",
  "displayName": "DeepSeek Chat",
  "endpoint": {"provider": "OPENAI_COMPATIBLE", "...": "..."},
  "enabled": true,
  "apiKeyConfigured": true,
  "version": 1
}
```

`apiKeyConfigured` 只表示 `apiKeyEnv` 指向的环境变量当前是否存在且非空；
服务端从不读取该值写日志或返回给客户端。`FAKE` provider 固定为 `true`。
设置页用它提示“密钥未配置”，但不会因此阻止保存档案。

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

### 5.1 工具目录

启用插件后，插件工具与内置工具出现在同一列表中：

```http
GET /api/v1/tools?cwd=D:/code/demo
```

响应示例（截断）：

```json
[
  {
    "name": "read",
    "description": "Read a UTF-8 text file with line numbers.",
    "inputSchema": "{\"type\":\"object\",\"properties\":{...},\"required\":[\"path\"]}",
    "risk": "LOW",
    "executionMode": "PARALLEL",
    "timeoutMs": 10000,
    "effect": "ALLOW",
    "requiresApproval": false
  }
]
```

- `cwd` 用于计算 `workspace_key`（`sha256(normalized cwd)`）；省略时使用进程当前目录，
  USER 规则通过 `GLOBAL` 键参与解析；
- 返回完整 `inputSchema`，供前端展示参数与后续 LLM 请求复用；不是只返回摘要；
- `effect` 是当前工作区解析后的结果（`ALLOW / ASK / DENY`），写入磁盘前会重新解析，
  不信任客户端回传；
- `cwd` 非法（如含 NUL）返回 400 `INVALID_ARGUMENT`。

### 5.2 策略管理（M6）

```http
GET /api/v1/tool-policies?cwd=D:/code/demo
PUT /api/v1/tool-policies
Content-Type: application/json

{
  "scope": "WORKSPACE",
  "cwd": "D:/code/demo",
  "toolName": "write",
  "effect": "ASK",
  "approvalMode": "ONCE",
  "riskLevel": "HIGH",
  "timeoutMs": 30000,
  "config": {}
}
```

- `GET` 返回工作区规则与 USER 全局规则（`workspaceKey` 为 64 个 `0`），字段包含
  `id/scope/workspaceKey/toolName/effect/approvalMode/riskLevel/timeoutMs/config/version`；
- `PUT` 是 upsert：同一 `(scope, workspace_key, tool_name)` 已存在时更新并递增 `version`；
  `riskLevel` 可省略（沿用工具默认风险），`config` 可为空但顶层值不能为 null；
- 策略解析优先级 `WORKSPACE > USER > 风险默认`；`DENY` 命中后不会被更宽规则覆盖；
- 未知 scope/effect/approvalMode/riskLevel、空 `toolName`、非法 `cwd` 返回 400
  `INVALID_ARGUMENT`；工具不存在返回 404 `TOOL_NOT_FOUND`；
- `approvalMode` 当前只做持久化，执行器仍按 `effect` 每次询问。

## 6. 插件 API（M5）

```http
GET  /api/v1/plugins
POST /api/v1/plugins
POST /api/v1/plugins/{id}/enable
POST /api/v1/plugins/{id}/disable
```

安装请求只接受宿主可读的**本地 jar 路径**（没有上传接口）：

```json
{
  "jarPath": "D:/code/pi-java/examples/pi-plugin-example/target/pi-plugin-example-0.1.0-SNAPSHOT.jar",
  "config": {}
}
```

`config` 必须是 JSON 对象，可省略；M5 只保存不使用，留给 v0.2 的插件配置。

响应 `201`：

```json
{
  "id": "com.piagent.example.hello",
  "version": "1.0.0",
  "apiVersion": "1.0",
  "jarPath": "D:\\code\\pi-java\\examples\\pi-plugin-example\\target\\pi-plugin-example-0.1.0-SNAPSHOT.jar",
  "status": "INSTALLED",
  "lastError": null,
  "installedAt": "2026-10-22T08:00:00Z",
  "updatedAt": "2026-10-22T08:00:00Z"
}
```

- 响应不返回配置原文与完整校验和；
- 安装只校验并落库，不执行插件代码；enable 才创建类加载器并调用 `start`；
- 安装时校验：jar 存在且 ≤ 64 MB、清单完整、入口类存在、API 主版本为 1、
  插件 id 未安装、jar 内不含 `com/piagent/plugin/api` 类；
- enable：重新检查 jar 与安装记录（路径、版本、API 版本、SHA-256 一致），
  加载失败返回 422 `PLUGIN_LOAD_FAILED` 并落库为 `FAILED`；
- disable：立即从实时工具目录移除并置 `DISABLED`；**不等待**活跃调用归零，
  已开始的 run 继续用旧快照与旧类加载器完成；
- enable/disable 对目标状态幂等；从 `FAILED`、`DISABLED`、`INSTALLED` 均可再次 enable；
- 重复安装返回 409 `PLUGIN_ALREADY_EXISTS`；未知插件返回 404 `PLUGIN_NOT_FOUND`；
- 应用重启后只自动恢复 `ACTIVE` 记录，恢复失败的插件置 `FAILED` 并记录 `lastError`。

停用后插件工具从 `GET /api/v1/tools` 消失；新 run 调用该工具得到
`工具未注册: <name>` 的 toolResult，run 本身仍正常完成。

## 7. 系统信息、Skills 与压缩

### 7.1 系统信息（M4）

```http
GET /api/v1/system/info
```

```json
{
  "version": "0.1.0-SNAPSHOT",
  "workspaceRoot": "D:\\code\\pi-java",
  "osName": "Windows 11"
}
```

Web UI 用它获取默认工作目录，避免把开发者机器路径硬编码进静态资源。
只暴露版本、进程工作目录与操作系统，不含密钥或环境变量。

### 7.2 技能目录（M7）

```http
GET /api/v1/skills?refresh=false
```

```json
[
  {
    "name": "deploy",
    "description": "打包与发布流程",
    "path": "C:\\Users\\me\\.pi-java\\skills\\deploy\\SKILL.md",
    "source": "C:\\Users\\me\\.pi-java\\skills"
  }
]
```

- 只返回 `name / description / path / source`，不返回正文；
- `refresh=true` 先清空缓存再扫描，用于“刚改了 SKILL.md 想立刻生效”；
- 没有配置任何技能目录时返回 `[]`（不是 404）。

```http
GET /api/v1/skills/{name}
GET /api/v1/skills/{name}/content
```

`/content` 返回去掉 frontmatter 的正文：

```json
{ "name": "deploy", "content": "# 发布流程\n第一步：执行 mvn -q package" }
```

未知技能返回 404 `SKILL_NOT_FOUND`。

### 7.3 手动压缩（M7）

```http
POST /api/v1/sessions/{id}/compact
```

```json
{
  "applied": true,
  "entryId": "01J...",
  "tokensBefore": 48210,
  "tokensAfter": 9120,
  "savedTokens": 39090,
  "prunedOnly": false,
  "summary": "## 目标\n...",
  "message": "压缩完成，上下文由 48210 token 降至 9120 token"
}
```

不需要压缩时同样返回 200：

```json
{
  "applied": false,
  "tokensBefore": 2380,
  "tokensAfter": 2380,
  "savedTokens": 0,
  "prunedOnly": false,
  "message": "未执行压缩：上下文仍可完整放入保留窗口（保留 20000 token），压缩不会带来收益"
}
```

| 状态码 | 场景 |
|---|---|
| 200 | 压缩成功或无需压缩（`applied` 区分） |
| 404 | 会话不存在 |
| 409 | 会话正在运行或等待审批 |

跳过原因分两种，措辞不同以便区分“内容太少”与“无需压缩”：
`没有足够的可摘要内容（至少需要两条参与上下文的消息）`、
`上下文仍可完整放入保留窗口…`。

### 7.4 健康检查（M9）

```http
GET /api/v1/health
```

计划返回数据库、Flyway、插件快照与活跃 run 数；尚未实现。

## 8. CLI（M11）

同一个可执行 jar 同时提供 Web 与 CLI，按首个参数分派：

| 启动方式 | 模式 |
|---|---|
| `java -jar pi-java-app.jar` | Web |
| `java -jar pi-java-app.jar serve ...` | Web |
| `java -jar pi-java-app.jar run\|chat\|sessions\|models\|tools ...` | CLI |

CLI 以 `WebApplicationType.NONE` 启动同一个 Spring 容器：不监听端口、不注册
Controller，但数据库、Flyway、demo 初始化、插件、Skills、模型与工具装配与 Web
完全一致。CLI 只调用应用层用例，不直接访问 Mapper，也不新增持久化路径。

### 8.1 命令总览

```text
pi run      [MESSAGE] [--cwd PATH] [--model ID] [--thinking LEVEL]
            [--title TITLE] [--session ID] [--json] [--timeout SECONDS] [--approve]
pi chat     [--cwd PATH] [--model ID] [--thinking LEVEL] [--session ID] [--title TITLE]
            [--plain]
pi sessions list [--cwd PATH] [--limit N]
pi sessions show ID
pi models list
pi tools list [--cwd PATH]
pi serve    [--port 8080] [--profile demo|mysql] [其他 Spring 参数]
```

- `--thinking` 取值 `OFF / LOW / MEDIUM / HIGH`，默认 `OFF`，大小写不敏感；
- `--cwd` 默认进程当前目录，必须是已存在目录；
- 新会话未指定 `--model` 时使用第一个启用档案；指定 `--session` 时沿用会话模型；
- `pi --help` 与各子命令 `--help` 输出用法；`pi --version` 输出版本。

### 8.2 `pi run`

非交互单次执行，适合脚本与 CI：

```bash
pi run "读取 pom.xml 并总结依赖" --cwd D:/code/demo
pi run "把结论写入 notes.md" --cwd D:/code/demo --approve
```

- `MESSAGE` 省略时从 stdin 读取全部内容，空白按参数错误处理；
- 默认流式打印助手文本、思考增量（`[思考]` 前缀）、工具开始/结束摘要与压缩提示；
- 审批事件在终端提示 `批准执行? [y/N]`，只接受 `y` 为批准，其他输入均为拒绝并附带
  原因 `cli-denied`；输入结束（EOF）时以 `no-input` 默认拒绝，并说明可加
  `--approve` 自动批准；
- `--approve` 显式放行本次 run 的全部审批，等价于 `decidedBy=cli-approve`；
- `--json` 抑制流式输出，只向 stdout 打印一个 JSON 对象：

  ```json
  {
    "sessionId": "01J...",
    "runId": "01J...",
    "status": "COMPLETED",
    "stopReason": "STOP",
    "turns": 1,
    "toolCalls": 0,
    "usage": {"input": 0, "output": 0, "cacheRead": 0, "cacheWrite": 0, "totalTokens": 0},
    "durationMs": 1234,
    "finalText": "……"
  }
  ```

  失败时额外带 `errorMessage`；审批提示与诊断信息只写 stderr，保证 stdout 可被 jq 解析；
- `--timeout` 为等待 run 结束的秒数，默认 600；
- 退出码：`0` 正常完成，`1` 运行失败/超时，`2` 参数或启动错误，
  `3` 至少一次审批被拒绝，`4` 被取消。审批拒绝优先于完成码，取消优先于拒绝。

### 8.3 `pi chat`

一次进程内的多轮对话。标准输入是真实终端时默认进入交互式界面（TUI），
否则退回逐行模式，保证管道与脚本行为不变。

交互式界面：

```text
  π pi-java · fake-demo · D:/code/demo
  修复登录页样式 · 01J...
  /help 命令 · Tab 补全 · Ctrl+C 取消/退出 · Ctrl+D 退出

┌─ pi ──────────────────────────────────────────
│ > 读取 README.md 并总结
└──────────────────────────────────────────────
```

- 输入区为方框样式；输入 `/` 时在光标后淡色列出全部命令，前缀唯一时直接显示
  待补全部分，`Tab` 弹出完整候选，`↑/↓` 翻命令历史；输入命令参数后提示自动隐藏；
- 助手文本青色流式输出，思考增量灰色，工具轨迹两行摘要，压缩与审批各一行；
- 审批以面板展示工具名、风险与参数：输入 `y`/`yes` 批准，直接回车或其他内容
  拒绝，Ctrl+C 或 EOF 视为“没有输入”，按 `no-input` 拒绝；
- Ctrl+C：空闲时第一次提示“再按一次 Ctrl+C 退出”，1.5 秒内再按退出；
  运行中按 Ctrl+C 取消当前 run，run 收尾后回到提示符，不退出进程；
- Ctrl+D 随时退出；
- `--plain` 强制逐行模式（提示符 `你> `，输出与旧版一致），适合录制终端日志。

逐行模式（管道、重定向、测试环境或显式 `--plain`）的斜杠命令：

- 普通文本 → 发起 run 并流式渲染，等待结束后回到提示符；
- `/exit`、`/quit` → 退出；
- `/help` → 命令帮助；
- `/abort` → 取消当前 run；运行中仍可输入该命令（其他输入提示运行中）；
- `/compact` → 手动压缩当前空闲会话；
- `/model <id>` → 切换会话模型并写 `MODEL_CHANGE` entry；
- `/tree` → 打印当前分支 entry 摘要；
- `/session` → 打印会话 id、模型、思考级别与状态；
- `/skill:name [args]` → 作为普通消息交给 Agent Loop 的技能解析器；
- EOF（Ctrl+D/Ctrl+Z）退出。

两种模式共用同一套斜杠命令实现；除此之外的 `/xxx`（如 `/write`、`/read`）
按普通消息处理。renderer 与审批语义和 `pi run` 一致，退出码恒为 `0`
（单次 run 失败只提示并继续对话）。

### 8.4 只读命令

```bash
pi sessions list --cwd D:/code/demo --limit 20
pi sessions show 01J...
pi models list
pi tools list --cwd D:/code/demo
```

- `sessions list` 按更新时间倒序，输出 id、状态、模型、标题与工作目录；
- `sessions show` 输出会话头与当前分支每个 entry 的序号、类型、角色与内容摘要，
  工具条目带名称与成功/失败，审批条目带决策状态；
- `models list` 输出 id、启用状态、provider、model 与密钥环境变量名；
  **只显示变量名和是否已设置，绝不输出值**；
- `tools list` 输出工具名、风险、策略效果（ALLOW/ASK/DENY）、执行模式与超时，
  `ASK` 额外标记需审批。

### 8.5 `pi serve`

Web 模式兼容入口，等价于无参数启动：

```bash
pi serve --port 8080 --profile demo
```

`--port` 映射为 `--server.port`，`--profile` 映射为 `--spring.profiles.active`，
其余参数原样交给 Spring Boot。默认绑定 `127.0.0.1`，不提供认证。

### 8.6 未提供命令

`plugins *`、`models add`、`sessions fork/resume` 仍通过 Web 设置中心或 HTTP API
完成；`sessions resume` 由 `pi chat --session ID` 覆盖。这些子命令不承诺兼容性，
v0.2 再评估是否加入。

## 9. Web UI

静态资源位于 `pi-java-app/src/main/resources/static/`，原生 HTML/CSS/JS ES modules，
无构建步骤，只依赖 `/api/v1`。布局为左侧会话栏 + 右侧消息流与输入框，移动端自动
折叠为上下布局。

```text
┌───────────────┬─────────────────────────────────────────┐
│ 会话侧栏       │ 顶栏：标题 / 上下文仪表 / 会话设置         │
│ + 新建会话     │ 消息流                                   │
│ 会话列表       │ - 用户/助手气泡                           │
│ 设置入口       │ - 工具卡片（工具名、参数、输出、错误态）    │
│               │ - 压缩/审批/模型切换历史条目               │
│               │ - 流式文本与光标                          │
│               │ 输入框：模型名 / 发送 / 停止               │
└───────────────┴─────────────────────────────────────────┘
```

交互覆盖：

1. 新建会话（标题、工作目录、模型档案、思考级别）→ 发送消息 → 流式增量 → 完成；
2. 工具卡片展示参数、输出与错误态；写工具触发审批弹窗，展示风险级别、工具名、
   脱敏参数、SHA-256 摘要与倒计时；批准/拒绝后写审批历史条目，多个待办串行展示，
   刷新页面通过 `GET /approvals?status=PENDING` 恢复；
3. 停止运行，显示 ABORTED，保留已输出内容；切换会话或刷新后通过
   `GET /sessions/{id}/run` 自动重连 SSE；
4. **运行中切换会话**：只断开前端事件流，不打断服务端 run；再次打开会话自动
   重连并补齐进度。运行中（含等待审批）发送的消息进入队列，本轮结束后按顺序
   自动发出，输入框上方显示排队提示与取消按钮；
5. 顶栏上下文仪表显示 `已用 / 窗口 · 百分比`，达到 70% / 88% 变色；
   “压缩”按钮只在空闲会话可用，内容不足时按后端说明提示无需压缩；
6. 会话设置弹窗支持重命名、切换模型与思考级别、归档；归档后输入框、压缩与
   设置控件全部禁用，历史仍可只读查看；
7. 侧栏按工作目录分组，组头显示目录名与数量，可折叠并记忆（`localStorage`）；
   搜索时自动展开匹配分组；每行的 `⋯` 菜单提供重命名与删除，删除前二次确认；
8. 历史条目已渲染压缩摘要、分支摘要、审批决策与模型切换，不只是普通消息；
9. 进程重启后自动取消遗留审批、闭合悬空工具调用并复位运行态，卡住的会话
   可以直接继续对话（见 2.8.1）；
10. 设置中心包含五个标签页：
   - **模型与密钥**：卡片式档案列表、密钥环境变量状态、行内新建/编辑/启停；
   - **技能**：扫描 `~/.pi-java/skills`，按需查看 `SKILL.md` 正文；
   - **插件**：按本地 jar 路径安装、启用/停用、展示最近错误；
   - **工具策略**：选择工具与作用域，设置 `ALLOW/ASK/DENY`、审批模式、风险与超时；
   - **系统**：版本、工作目录、操作系统与安全须知。
11. 输入 `/read README.md`、`/ls .`、`/write notes.txt 你好`、`/skill:名称 任务`、
   `/tool <工具名> <JSON>` 可在 Fake 模型下体验完整链路。

前端只通过 `textContent` 写入服务端与模型文本，禁止 `innerHTML`；所有密钥相关
字段只展示环境变量名与“是否已配置”。

## 10. 版本与兼容

- API 版本在路径中；破坏性变更升 `/api/v2`；
- 响应只增字段不删字段；客户端忽略未知字段；
- 错误 code 稳定，message 可调整；
- CLI 参数只增不删；废弃参数先告警一个 minor 版本；
- OpenAPI 描述 v0.1 暂不生成，接口以本文与 DTO 测试为准。

## 11. 相关文档

- [运行时与事件](04-runtime-and-events.md)
- [插件 SDK](06-plugin-sdk.md)
