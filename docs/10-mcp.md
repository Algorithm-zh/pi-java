# 10 MCP 客户端

> 状态：M12 已实现（2026-10-24）。MCP 服务器是**配置驱动**的外部能力来源，
> 不是插件；远程工具与内置工具、插件工具共享同一套目录、策略、审批与超时管线。

## 1. 定位与边界

MCP（Model Context Protocol）让 pi-java 在不重新打包的前提下接入外部工具服务器。
M12 只做“客户端 + 工具发现”：

- 支持 `stdio` 与 Streamable HTTP 两种传输；
- 启动时完成 `initialize` 握手并调用 `tools/list` 发现工具；
- 远程工具映射为 `ToolDescriptor`，带 `<server>.<tool>` 命名空间；
- 风险级别按“显式覆盖 → 服务器默认 → 名称启发式”映射，进入既有审批管线；
- 连接、超时、远程错误都转成 `isError=true` 的工具结果，不打断 Agent Loop。

不在范围内：MCP resources/prompts/sampling、服务端通知处理、动态工具刷新、
OAuth 与凭据管理、Web/CLI 管理界面、MCP 服务器持久化到数据库。

## 2. 配置

MCP 服务器只存在于配置文件，**没有数据库表与迁移**。`application.yml` 默认关闭：

```yaml
pi:
  mcp:
    servers: []
```

启用一个 stdio 服务器：

```yaml
pi:
  mcp:
    servers:
      - name: filesystem
        transport: stdio
        stdio:
          command: npx
          args: ["-y", "@modelcontextprotocol/server-filesystem", "D:/code/demo"]
        timeouts:
          request: 60s
          discovery: 20s
        risks:
          default-level: medium
          tools:
            write_file: high
```

启用一个 Streamable HTTP 服务器（敏感值只写环境变量占位符）：

```yaml
pi:
  mcp:
    servers:
      - name: remote
        transport: http
        http:
          url: https://mcp.example.com/mcp
          headers:
            Authorization: "Bearer ${MCP_TOKEN}"
```

字段说明：

| 字段 | 必填 | 默认 | 说明 |
|---|---|---|---|
| `name` | 是 | — | 小写字母、数字、连字符，以字母开头，最长 40；同时是工具命名空间 |
| `transport` | 否 | `stdio` | `stdio` 或 `http`（也接受 `streamable-http`） |
| `stdio.command` / `args` / `env` | stdio 必填 | 空 | 子进程命令；`env` 追加或覆盖环境变量 |
| `http.url` / `headers` | http 必填 | 空 | 只接受 `http://` 或 `https://` |
| `timeouts.request` | 否 | `60s` | 单次 `tools/call` 的请求超时，最大 10 分钟；也是工具执行的 descriptor 超时 |
| `timeouts.discovery` | 否 | `20s` | `initialize` 与每页 `tools/list` 的超时，最大 2 分钟 |
| `risks.default-level` | 否 | `auto` | `auto`/`low`/`medium`/`high`/`critical` |
| `risks.tools` | 否 | 空 | 按**远程工具名**覆盖风险级别；含点的名字用 `"[a.b]"` 写法 |

配置结构错误（未知传输、缺少 `command`/`url`、非法风险级别、超时越界）在绑定阶段
直接失败；连接失败只影响该服务器，应用照常启动。密钥只能写成 `${ENV_VAR}`，
由 Spring 从环境变量解析，仓库与数据库都不保存明文。

## 3. 传输

### 3.1 stdio

- 启动子进程，stdin/stdout 使用**换行分隔的 JSON-RPC 2.0**，UTF-8；
- stderr 单独排队读取并写 DEBUG 日志，避免管道写满阻塞子进程；
- 请求按 `id` 匹配响应，期间收到的通知直接丢弃；
- 有截止时间的阻塞等待，超时抛 `MCP_TIMEOUT`，不会无限挂住 run；
- Windows 下以 `.cmd`/`.bat` 结尾的命令自动包一层 `cmd.exe /c`；
- 关闭时先终止子进程树（`descendants()` + 进程本身），再关闭流。

### 3.2 Streamable HTTP

- 每个请求 `POST` 到配置的 URL，`Accept: application/json, text/event-stream`；
- 响应可以是单个 JSON，也可以是 SSE；SSE 逐行累积 `data:`，只取与请求 `id`
  匹配的那条消息，其他事件忽略；
- 服务端返回的 `Mcp-Session-Id` 会被记住并附加到后续请求；
- 非 2xx 响应转成 `MCP_TRANSPORT`，错误信息最多携带响应体前 500 字节；
- 关闭时若存在会话，发送一次 `DELETE`（5 秒超时）；
- 连接超时 10 秒，建连与读取都受每次请求的截止时间约束。

## 4. 握手与工具发现

`connect()` 固定执行两步：

1. `initialize`：声明 `protocolVersion=2025-06-18`、`clientInfo={pi-java, 0.2.0-SNAPSHOT}`；
   响应缺少 `protocolVersion` 视为协议错误；成功后发送 `notifications/initialized`；
2. `tools/list`：支持 `nextCursor` 分页，最多 50 页；跳过没有名字或
   `inputSchema` 不是 JSON 对象的工具（LLM 工具协议要求对象 schema）。

工具描述为空时补默认文本，超长截断到 1000 字符。工具定义在启动时**一次性冻结**：
远程新增/删除工具需要重启进程才生效，这样 run 快照与调用目标始终一致。

## 5. 命名空间与冲突

远程工具名先清洗再加前缀：

```text
服务器 github + 远程工具 "Search Issues"  ->  github.search-issues
服务器 github + 远程工具 "1password.read" ->  github.tool-1password.read
服务器 github + 超过 128 字符的名字         ->  github.<截断部分>-<hash>
```

规则：转小写；非法字符连续段替换为 `-`；折叠重复分隔符；去掉首尾分隔符；
首字符不是字母时加 `tool-`；超过 `ToolName` 的 128 字符上限时截断并追加短哈希。

冲突处理：

| 情况 | 行为 |
|---|---|
| 同一服务器内清洗后重名 | 跳过后出现的工具并告警 |
| 与内置工具、插件工具或其他服务器冲突 | 该服务器注册失败（`MCP_TOOL_CONFLICT`），其余服务器不受影响 |
| 工具名不以 `<server>.` 开头 | 注册失败，防止越界注册 |

## 6. 风险与审批

风险映射优先级：

1. `risks.tools.<远程工具名>` 显式覆盖；
2. `risks.default-level`，`auto` 时跳过；
3. 名称启发式：包含
   `write/edit/delete/remove/exec/run/shell/command/create/update/move/rename/install/upload/send/post`
   之一 → `HIGH`，否则 `MEDIUM`。

MCP 工具默认不直接放行：`MEDIUM`/`HIGH` 按 [`ToolAccessPolicy`](../pi-java-domain/src/main/java/com/piagent/domain/governance/service/ToolAccessPolicy.java)
默认需要审批，`CRITICAL` 默认拒绝，`LOW` 才放行。需要更细的控制时，直接用策略表
按完整工具名（如 `github.create_issue`）配置 `ALLOW`/`ASK`/`DENY`。

## 7. 执行、超时与断线

- 所有 MCP 工具声明为 `SEQUENTIAL`：远程调用可能共享服务器状态，不参与同批并行；
- 执行走同一条工具管线：预检 → 策略/审批 → 预算 → 调用 → 配对 `toolResult`；
- 远程返回 `isError=true` 时文本照常回写，`details.errorCode=MCP_TOOL_ERROR`；
- 本地失败映射：

| 错误码 | 场景 |
|---|---|
| `MCP_TIMEOUT` | 请求在截止时间内没有响应 |
| `MCP_TRANSPORT` | 进程启动失败、HTTP 非 2xx、连接异常 |
| `MCP_PROTOCOL` | JSON-RPC error、响应缺少必要字段、流畸形 |
| `MCP_STREAM_CLOSED` | 等待响应期间子进程/流关闭 |
| `MCP_CANCELLED` | 关闭宿主时仍在等待的请求 |
| `MCP_TOOL_FAILED` | 其他未预期异常 |

- **不做自动重试**：请求一旦发出，可能已产生副作用（写文件、发消息），重试会造成重复执行；
- **断线重连**：仅当传输明确报告已关闭（典型是 stdio 子进程退出）时，下一次调用前
  重建客户端并重新握手；HTTP 会话过期只会得到一次工具错误，由模型或用户决定下一步；
- 重连沿用启动时发现的工具定义，不会重新执行 `tools/list`。

宿主关闭时关闭全部客户端、移除 MCP 工具，并从运行时目录删除对应服务器命名空间。

## 8. 启动与故障隔离

`McpStartupRunner` 在 Spring 容器就绪后调用 `McpHost.start()`：

- 逐个服务器连接，任何异常只记录 WARNING，不阻止应用启动；
- 成功时 INFO 日志输出服务器名与工具数量；
- 未配置服务器时 `servers: []`，零开销；
- 服务器工具进入 `RuntimeToolRegistry` 的实时目录，`/api/v1/tools` 与模型工具列表
  会立即出现；run 开始时的快照语义不变。

## 9. 安全

- MCP 工具由**外部服务器**执行，pi-java 的路径沙箱不会约束其内部行为；
  只接入可信服务器，必要时用容器/低权限账号隔离；
- 远程 HTTP 建议使用 HTTPS；`Authorization` 等头只写 `${ENV_VAR}`；
- 启动日志与工具错误不输出请求头、token 或完整环境变量；
- 工具审批按风险级别生效，`CRITICAL` 默认拒绝；
- 服务器返回的文本按不可信内容处理，Web UI 仍走安全 Markdown 渲染。

## 10. 已知限制

- 不支持 MCP resources、prompts、sampling 与 elicitation；
- 服务端通知（`notifications/*`）只被忽略，不触发目录刷新；
- 工具目录变更需要重启进程；
- 一个服务器名只能出现一次；HTTP 传输不自动重试，也不做 OAuth 授权流程；
- `Mcp-Session-Id` 只保存在内存，无法跨进程恢复远程会话；
- 尚无 MCP 管理 API 与 UI，配置变更同样需要重启。

## 11. 测试与验收

| 层次 | 覆盖 |
|---|---|
| 单元 | 命名清洗/截断/越界、风险映射优先级、分页发现、初始化通知、content 合并 |
| stdio 集成 | 真实子进程：发现、调用、远端错误、超时、关闭 |
| HTTP 集成 | 本地 HTTP 服务：JSON 与 SSE 响应、会话头、DELETE、HTTP 500 |
| 宿主 | 注册/风险/串行、调用分派、断线重连、超时错误映射、冲突隔离、关闭移除 |
| 端到端 | Spring + Fake 模型 + stdio 假服务器：`/tool` → `tool.completed` → `run.completed` |

`mvn verify` 全绿（跳过 repackage 时 671 个测试，2 个符号链接用例条件跳过）。

## 12. 相关文档

- [整体架构](01-architecture.md)
- [运行时与事件](04-runtime-and-events.md)
- [插件 SDK](06-plugin-sdk.md)
- [M12 里程碑记录](milestones/M12.md)
