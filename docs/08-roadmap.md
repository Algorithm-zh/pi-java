# 08 路线图

> 状态：M0 进行中。里程碑顺序不可跳跃；每个里程碑以“可运行 + 可测试 + 有文档 + 有提交”为完成标准。

## 1. 交付策略

- 小步增量：每个里程碑只引入一组内聚能力，不同时铺开 Web、插件和子代理；
- 从领域到入口：先让领域规则可测，再接数据库，再接 LLM/工具，最后接 Web/CLI；
- 每个里程碑结束执行 `mvn -q verify`，更新对应文档与模块 README，单独 git commit；
- 不主动 push；发布 tag 需用户明确确认；
- 任何时刻主干可构建、可启动 demo（M4 起）。

## 2. 里程碑总览

| 里程碑 | 主题 | 交付 | 验收 | 提交信息 |
|---|---|---|---|---|
| **M0** | 骨架与文档 | 6+1 Maven 模块、Checkstyle/ArchUnit、Flyway 目录、docs/ADRs | `mvn -q -DskipTests package`；文档链接可解析 | `chore: bootstrap pi-java ddd skeleton and architecture docs` |
| **M1** | 会话领域与持久化 | `ChatSession`、`SessionEntry`、消息模型、投影、分支策略、V1、MyBatis 仓储 | 投影/分支/fork/配对/ULID 单测；H2 仓储集成测试 | `feat(session): tree session log and context projection` |
| **M2** | 模型接入 | `ModelProfile`、`LlmGateway`、Fake + OpenAI-compatible 适配器、V2、模型 API | WireMock 流式/重试/取消/错误；Fake 脚本测试 | `feat(model): model profiles and streaming llm gateway` |
| **M3** | 工具与沙箱（只读） | `ToolDescriptor/Catalog`、PathSandbox/Resolver、read/ls/grep/glob、V3 | 路径穿越/符号链接/截断/超时；参数校验；策略默认值 | `feat(tool): read-only tools and path sandbox` |
| **M4** | Agent Loop + Web demo | Run 状态机、SSE、Web UI、Fake E2E、RUN_SUMMARY | 状态机、取消、SSE 回放、MockMvc、浏览器手工验证 | `feat(agent): agent loop, sse and read-only web demo` |
| **M5** | 插件 SDK | plugin-api、child-first ClassLoader、PluginRuntime、示例插件、V4、插件 API | 类加载隔离、注册回滚、卸载、契约纯度、示例独立编译 | `feat(plugin): zero-dependency plugin sdk and example` |
| **M6** | 审批 + 写工具 | ApprovalRequest、ToolPolicyRule、审批 API/SSE/UI、write/edit/bash、V5 | 审批并发/过期/取消、策略优先级、Windows 进程超时、脱敏 | `feat(governance): approval workflow and write tools` |
| **M7** | 压缩 + Skills | CompactionPlanner、TokenEstimator、SkillCatalog、system prompt、`/compact`、`/skill:` | pi 黄金样本、切点配对、摘要收敛、frontmatter、缓存失效 | `feat(context): compaction and skills` |
| **M8** | 子智能体 | SubagentPolicy、subagent 工具、SPAWN/FORK、子会话树、SSE/UI | 深度上限、工具子集、审批不继承、取消级联、结果 stopReason | `feat(agent): synchronous subagents` |
| **M9** | CLI + 打磨 + v0.1.0 | picocli 命令、MySQL smoke、demo 脚本、用户手册、截图、tag | CLI 冒烟、Fake E2E、本机 MySQL 启动、浏览器完整走查 | `feat(cli): picocli entry and v0.1.0 demo`，tag `v0.1.0` |

## 3. 各里程碑详情

### M0 骨架与文档

交付：

- 根 pom、7 个模块 pom（含示例插件）；
- `pi-java-app` 启动类、`application.yml`（demo/mysql）；
- Checkstyle、ArchUnit、Flyway 目录；
- docs/00–08、4 个 ADR、模块 README、M0 记录。

验收：

```bash
mvn -q verify
```

### M1 会话领域与持久化

范围：

- `domain.shared`：ULID、错误码、异常、Guard；
- `domain.session`：聚合、entry、payload、消息 sealed、投影器、分支策略；
- V1 两表；MyBatis 仓储与 PO；
- 应用层：创建会话、追加消息、读取分支、fork/branch 用例；
- 不接 LLM、不接 Web。

验收：

- 同一 entry 序列的投影可重复；压缩语义正确；
- fork/分支不复制历史、seq 连续；
- H2 集成测试覆盖唯一键/CAS/级联。

### M2 模型接入

范围：

- `domain.model`、`ModelProfile`、`LlmGateway` 端口；
- Fake provider（脚本化流、错误、取消）；
- OpenAI-compatible provider（JDK HttpClient + SSE 解析）；
- V2；模型配置用例与 API；
- 密钥只读环境变量。

验收：

- WireMock 覆盖流式、429/5xx、断流、取消、畸形 JSON；
- 重试只发生在 adapter，且首个 delta 后不重试；
- 模型 API 拒绝疑似密钥值。

### M3 工具与沙箱（只读）

范围：

- `domain.tool`：ToolDescriptor/Catalog/Planner/ArgumentGuard；
- `domain.governance.PathSandbox`；
- infrastructure：PathResolver、read/ls/grep/glob；
- V3 策略表与默认策略；
- 工具列表用例/API。

验收：

- 路径穿越、符号链接、盘符切换被拒绝；
- 输出截断 64 KB；超时可取消；
- 未配置的未知工具默认 DENY。

### M4 Agent Loop + 首个 Web demo

范围：

- `application.agent`：RunLifecycle、TurnExecutor、ToolCallCoordinator、ContextAssembly；
- RunRegistry、SSE Hub、事件缓冲与回放；
- 原生 Web UI：新建会话、流式消息、工具卡片、取消；
- RUN_SUMMARY entry；
- demo profile 可启动。

验收：

- 状态机与取消测试；
- SSE 事件顺序、Last-Event-ID 回放；
- Fake LLM 浏览器完整走查；
- 无 API Key 可演示。

### M5 插件 SDK

范围：

- `pi-java-plugin-api` 全部契约；
- PluginRuntime、child-first ClassLoader、注册/回滚/卸载；
- V4、插件管理 API；
- 示例插件独立编译；
- ArchUnit 契约纯度。

验收：

- 安装 → enable → 工具可见可调 → disable → 工具消失；
- 运行中的 run 使用旧快照完成；
- 插件无法加载宿主内部类（类隔离测试）。

### M6 审批 + 写工具

范围：

- ApprovalRequest 状态机、ToolPolicyRule、ApprovalPort；
- 审批 API/SSE/UI；参数脱敏与 digest；
- write/edit/bash；ProcessRunner（Windows cmd / Unix bash）；
- V5。

验收：

- 允许/拒绝/超时/取消并发测试；
- 策略优先级；
- Windows 子进程超时与树清理；
- 审批决策与 entry 双写一致。

### M7 压缩 + Skills

范围：

- TokenEstimator、CompactionPlanner、摘要调用与收敛回退；
- SkillCatalog/扫描/frontmatter/prompt；
- system prompt 组装；
- `/compact`、`/skill:name`。

验收：

- pi 黄金样本投影；
- 切点不落 toolResult；
- 摘要不收敛时剪枝并继续；
- skill 缓存 mtime 失效。

### M8 子智能体

范围：

- SubagentPolicy、subagent 工具、SPAWN/FORK；
- 子会话创建与 entry 复制（FORK）；
- 权限交集、取消级联；
- SSE/UI 子会话时间线。

验收：

- 深度上限移除 subagent 工具；
- 工具子集与沙箱交集；
- 父取消子取消；
- 结果回写结构化。

### M9 CLI + 打磨 + v0.1.0

范围：

- picocli 全部命令；
- 用户手册、demo 脚本、截图；
- MySQL 本机 smoke；
- 依赖清理、覆盖率复核、tag。

验收：

- 验收清单（见 [00 愿景与范围](00-vision-and-scope.md) §7）逐项通过；
- `v0.1.0` tag 指向最终提交。

## 4. 关键依赖

```text
M0 ─► M1 ─► M2 ─► M3 ─► M4 ─► M5 ─► M6 ─► M7 ─► M8 ─► M9
                  │           │
                  └─ 只读工具  └─ 插件必须先有稳定工具目录
```

- M2 与 M3 可在 M1 后并行实现，但 M4 依赖两者；
- M5 依赖 M3 的工具目录抽象；
- M6 依赖 M5 的插件拦截器与 M3 的沙箱；
- M7 依赖 M4 的上下文组装；
- M8 依赖 M4 的 run 生命周期与 M6 的审批。

## 5. 发布与版本

- 仓库版本 `0.1.0-SNAPSHOT`，M9 发布 `0.1.0`；
- tag 只在用户确认后创建；不自动 push；
- 每次里程碑提交使用 Conventional Commits；
- 发布说明包含：新增能力、已知限制、启动方式、验收结果。

## 6. v0.2 候选

- MCP 客户端与工具动态发现；
- Anthropic 原生 provider；
- 异步/并行子代理与远程执行；
- 插件热重载、签名与权限声明；
- 多实例部署、Redis 事件总线与缓存；
- 图片/多模态输入、TUI；
- OpenAPI 生成与更完整的契约测试；
- 会话归档、配额与成本统计。

## 7. 风险与应对

| 风险 | 影响 | 应对 |
|---|---|---|
| entry envelope 反复改 | 数据迁移困难 | M0 冻结 envelope；payload 只加字段；黄金样本 round-trip |
| 插件契约泄漏内部类型 | 第三方无法独立升级 | plugin-api 零依赖；ArchUnit；示例独立编译 |
| 审批/取消/SSE 竞态 | 卡死或重复写 | 显式状态机；单 session 单 run；CAS；取消令牌；恢复流程 |
| Windows 路径/进程差异 | 工具行为不一致 | PathResolver/ProcessRunner 抽象；条件测试；本机优先验证 |
| H2/MySQL 方言差异 | 生产迁移失败 | Flyway 分目录；CI H2 + 本机 MySQL smoke |
| token 估算偏差 | 压缩不触发/不收敛 | 字符启发式 + usage 锚点；剪枝回退 |
| 范围膨胀 | 无法交付 v0.1 | 非目标清单；里程碑门禁；新想法进 v0.2 |

## 8. 完成定义（Definition of Done）

一个里程碑只有同时满足以下条件才算完成：

1. 代码实现并通过 `mvn -q verify`；
2. 新行为有单元/集成测试，失败路径有覆盖；
3. 相关文档、DDL、API 契约已更新；
4. demo 或 CLI 可实际运行验证；
5. 没有硬编码密钥与越界依赖；
6. 单独 git commit，信息符合规范；
7. 已知限制写入文档，不隐藏在代码注释里。
