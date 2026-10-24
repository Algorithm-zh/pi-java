# pi-java-app

唯一装配点与启动模块。

## 职责

- `PiJavaApplication`：Spring Boot 启动、组件扫描 `com.piagent`；
- `cli.CliLauncher`：`WebApplicationType.NONE` 启动同一容器，消费命令行参数后退出；
- `application.yml`：demo / mysql profile，密钥只来自环境变量；
- Flyway 分厂商迁移目录：`db/migration/h2` 与 `db/migration/mysql`；
- 静态 Web UI：`src/main/resources/static/`；
- 跨模块集成测试与 ArchUnit 架构测试。

## 启动

```bash
mvn -q -DskipTests package
java -jar pi-java-app/target/pi-java-app-0.1.0-SNAPSHOT.jar
```

同一个 jar 也提供 CLI：`java -jar ... run "任务"`、`chat`、`sessions`、`models`、
`tools`、`serve`。CLI 模式不启动 Web 服务器，退出后进程结束。`chat` 在真实
终端里是对话框式界面（方框输入、Tab 补全、彩色流式、Ctrl+C 取消），管道环境
自动回退逐行模式，`--plain` 可强制逐行。

开发期可用 `mvn -q -pl pi-java-app spring-boot:run`（需先执行一次
`mvn -q -DskipTests install`，让依赖模块进入本地仓库）。

默认 `demo` profile：H2 文件库 + Fake LLM，无需 API Key。
MySQL 使用 `--spring.profiles.active=mysql`，连接信息通过 `PI_MYSQL_*` 环境变量注入。

默认只监听 `127.0.0.1`；v0.1 无鉴权，禁止直接暴露公网。

打包前需停止正在运行的实例，否则 Windows 会锁住 `pi-java-app-*.jar`。

## 测试

- `ModuleBoundaryTest`：模块依赖方向；
- `SessionPersistenceIntegrationTest`：H2 上的仓储、唯一键、CAS、级联；
- `SessionApplicationIntegrationTest`：创建 → 追加 → 投影 → fork 的端到端路径；
- `AgentLoopIntegrationTest`：Fake 模型 + 真实工具 + H2 的完整 run；
- `RunSseIntegrationTest`：真实 Spring MVC 异步链路的 SSE 事件顺序与 `Last-Event-ID` 回放；
- `PluginHttpIntegrationTest`：动态生成插件 jar，验证安装/启用/停用与 404/409/422；
- `PluginEndToEndIntegrationTest`：插件工具经 Fake 模型 `/tool` 调用的完整 SSE 链路；
- `ApprovalPersistenceIntegrationTest`：V5 审批往返、按会话/状态查询、pending CAS、级联；
- `ApprovalEndToEndIntegrationTest`：`/write` → SSE `approval.requested` → HTTP 决策 →
  `approval.resolved` → 工具执行/拒绝的完整链路（H2 + 真实 Spring MVC）；
- `ContextEndToEndIntegrationTest`：技能来自配置目录、`/skill:` 落库展开、
  `/compact` 业务结论与 `SKILL_NOT_FOUND` 映射（M7）；
- `CliEndToEndIntegrationTest`：`run` 的 JSON 输出、写工具审批批准/拒绝/EOF 默认拒绝、
  只读命令、`chat` 多轮与 `/tree`、参数错误与根帮助（M11）；
- `McpEndToEndIntegrationTest`：配置 stdio 假 MCP 服务器后，`/tool echo.echo`
  经 Fake 模型得到 `tool.completed` 与 `run.completed`（M12）。

测试 profile 位于 `src/test/resources/application-test.yml`，使用内存 H2 与
`db/migration/h2`；测试之间直接清理业务表，保证可重复执行。

## 配置

M7 新增的运行时配置（均有默认值，零配置可启动）：

```yaml
pi:
  agent:
    context:
      context-window: 128000     # 兜底窗口（实际以模型端点为准）
      reserve-tokens: 16384
      keep-recent-tokens: 20000
      min-growth-tokens: 2000    # 两次自动压缩之间的最小新增 token
    skills:
      - "~/.pi-java/skills"      # 按顺序优先；重名先到先得
```

`~` 会展开为 `user.home`；不可读目录、无法解析的 `SKILL.md` 只跳过，不影响启动。

M12 新增 MCP 配置（默认关闭）：

```yaml
pi:
  mcp:
    servers:
      - name: filesystem
        transport: stdio
        stdio:
          command: npx
          args: ["-y", "@modelcontextprotocol/server-filesystem", "D:/code/demo"]
```

连接失败只告警，不阻止启动；远程工具自动带 `<server>.` 前缀进入工具目录。
完整字段、风险映射与限制见 [10 MCP 客户端](../docs/10-mcp.md)。

## 当前状态

M1 完成启动类、demo/mysql/test 配置、ArchUnit、Flyway V1 与会话集成测试；
M2 增加 V2 迁移与模型档案 H2 往返/CAS 集成测试；M3 增加 V3 策略表与
工具策略往返/工作区查询/CAS 集成测试；M4 增加 Agent Loop 集成测试、SSE 集成测试
与只读 Web UI（`src/main/resources/static/`）；M5 增加 V4、`PluginStartupRunner`
启动恢复、插件 HTTP 与端到端集成测试，以及动态插件 jar 测试夹具；M6 增加 V5、
审批持久化/SSE 端到端测试，以及 Web 审批弹窗（队列、倒计时、刷新恢复、批准/拒绝）；
M7 增加技能/压缩端到端测试与 `pi.agent.context` / `pi.agent.skills` 配置，
Web UI 已有 `compaction.started/completed` 状态提示，**无新增迁移**。
M12 增加 `McpStartupRunner`、`pi.mcp.servers` 配置、MCP 端到端集成测试与
动态 stdio 假服务器夹具，**同样无新增迁移**。
详见 [M12 记录](../docs/milestones/M12.md)。
