# 01 整体架构

> 状态：M0 冻结。模块数量、依赖方向与限界上下文划分在 v0.1 内不再调整；新增能力优先通过插件或上下文内扩展完成。

## 1. 架构总览

```text
                         ┌──────────────────────────────────────────┐
                         │              pi-java-app                 │
                         │  Spring Boot 启动 / Flyway / 静态 Web UI  │
                         │  配置绑定 / 架构测试（ArchUnit）           │
                         └───────────────┬──────────────────────────┘
                                         │ 装配全部实现
              ┌──────────────────────────┴──────────────────────────┐
              │                                                     │
    ┌─────────▼──────────┐                               ┌──────────▼─────────┐
    │  pi-java-trigger    │                               │ pi-java-           │
    │  REST / SSE / CLI   │                               │ infrastructure     │
    │  只依赖 application │                               │ LLM / MyBatis /    │
    └─────────┬──────────┘                               │ 工具 / 插件 / 缓存 │
              │                                          └──────┬──────┬──────┘
              │                                                 │      │
              │          ┌──────────────────────────┐           │      │
              └─────────►│    pi-java-application   │◄──────────┘      │
                         │ 用例编排 / Agent Loop /  │                  │
                         │ 事务边界 / 读模型         │                  │
                         └────────────┬─────────────┘                  │
                                      │                                │
                         ┌────────────▼─────────────┐                  │
                         │     pi-java-domain       │                  │
                         │ 7 个限界上下文 / 纯 JDK   │                  │
                         └──────────────────────────┘                  │
                                                                       │
                         ┌──────────────────────────┐                  │
                         │   pi-java-plugin-api     │◄─────────────────┘
                         │   仅 JDK 的插件稳定契约   │
                         └──────────────────────────┘
```

依赖方向（箭头表示“可以依赖”）：

```text
app            -> trigger, infrastructure
trigger        -> application
infrastructure -> application, domain, plugin-api
application    -> domain
domain         -> JDK
plugin-api     -> JDK
```

反向依赖一律禁止，由 `ModuleBoundaryTest` 在 `mvn verify` 中强制。

## 2. Maven 模块

根坐标 `com.piagent:pi-java:0.1.0-SNAPSHOT`，根包 `com.piagent`。

| 模块 | 允许依赖 | 职责 | 明确禁止 |
|---|---|---|---|
| `pi-java-domain` | 仅 JDK | 7 个上下文的聚合、实体、值对象、领域服务、仓储/端口接口、共享内核 | Spring、Jackson、MyBatis、Web、插件 API |
| `pi-java-plugin-api` | 仅 JDK | 第三方插件稳定契约：工具、命令、hook、prompt section、日志 | Spring、Jackson、domain/application 类型 |
| `pi-java-application` | domain、Spring stereotype/tx | 用例编排、Agent Loop、上下文组装、事务边界、读模型 | MyBatis、Jackson、Web、插件 API、基础设施实现 |
| `pi-java-infrastructure` | domain、application、plugin-api、驱动库 | LLM 适配器、MyBatis 仓储、内置工具、路径解析、插件加载、Caffeine 缓存 | trigger |
| `pi-java-trigger` | application、Spring MVC、picocli | Controller、SSE、DTO 映射、CLI 命令、参数校验 | 基础设施、Mapper、领域仓储实现 |
| `pi-java-app` | 全部 | 唯一装配点、启动类、Flyway、`application.yml`、静态 UI、ArchUnit | 业务逻辑 |
| `examples/pi-plugin-example` | plugin-api（provided） | 示例插件与契约纯度验证 | 其他内部模块 |

### 为什么没有 `pi-java-types` / `pi-java-api`

- 共享原语（ULID、错误码、领域异常）只服务于 domain，放在 `domain.shared` 即可；
  单独建 types 模块最终会变成无边界垃圾场。
- HTTP DTO 属于 trigger，应用层使用自己的 `Command`/`Query`/`ReadModel` record；
  不为“复用”把 Web 契约下沉到应用层。
- 插件契约独立成 `plugin-api`，是因为它面向第三方、需要零依赖；它不承担内部共享职责。

## 3. 七个限界上下文

全部位于 `pi-java-domain` 内，以包隔离；每个包内再按 `model / service / port` 组织。

| 上下文 | 包 | 核心职责 | 不负责 |
|---|---|---|---|
| 会话 session | `domain.session` | entry 树、分支、fork、消息模型、上下文投影 | 调用 LLM、执行工具 |
| 运行时 agent | `domain.agent` | Run 状态机、turn/step、预算、停止原因、子代理策略 | 持久化 Run 本身 |
| 模型 model | `domain.model` | 模型档案、能力、选择策略、LLM 端口契约 | HTTP/SSE 细节、重试实现 |
| 工具 tool | `domain.tool` | 工具描述、调用、结果、目录与参数守卫 | 具体文件/进程实现 |
| 治理 governance | `domain.governance` | 工具策略、审批状态机、路径沙箱、风险级别 | 等待 UI、数据库投影 |
| 上下文 context | `domain.context` | token 估算、压缩计划、Skill 描述与 prompt 格式化 | 文件扫描、YAML 解析 |
| 扩展 extension | `domain.extension` | 插件安装记录、描述、兼容性与激活策略 | ClassLoader、Spring 容器 |

### 上下文映射

```text
session ◄──── agent          （run 追加 entry，读取投影）
session ◄──── context        （压缩结果写 COMPACTION entry）
session ◄──── governance     （审批决策写 APPROVAL entry）
agent   ────► model          （通过 LlmGateway 端口）
agent   ────► tool           （通过 ToolRegistry/ToolInvoker 端口）
agent   ────► governance     （审批与沙箱端口）
agent   ────► context        （压缩与 skills 目录）
tool    ◄──── extension      （插件工具映射为 ToolDescriptor）
```

关系原则：

- **会话是中心，但不是上帝对象。** 它只定义 entry 与投影，不调用模型或工具。
- **agent 是编排者。** 它持有 Run 状态，通过端口驱动其他上下文，不直接操作数据库。
- **extension 与 tool 通过基础设施映射。** 领域层不引用插件类型；插件工具在 infrastructure 转成 `ToolDescriptor`。

### 三个刻意的建模取舍

1. **不建 subagent 上下文。** 子代理就是 `origin=SUBAGENT` 的子会话；
   权限、深度、工具子集由 `agent.SubagentPolicy` 约束，结果作为父会话工具结果回写。
2. **不建 workspace 上下文。** 工作区就是 session 的 `cwd` + `sandbox` 快照；
   策略以 `workspace_key = sha256(normalized cwd)` 关联。v0.1 没有工作区生命周期。
3. **工具定义不是聚合根。** 它是不可变目录项，使用 `ToolDescriptor` + `ToolCatalog`；
   只有调用（`ToolInvocation`）是运行态对象，调用结果写入会话 entry。

## 4. 分层职责

### 4.1 domain

- 表达业务规则与不变量，不关心存储、序列化、框架。
- 聚合根负责状态迁移；领域服务负责跨实体规则；端口接口表达“需要外部提供什么”。
- 时间通过 `Clock`、ID 通过 `IdGenerator` 注入，便于测试。
- 允许的行为：抛出领域异常、返回领域结果、发出领域事件对象（由应用层发布）。

### 4.2 application

- 一个用例一个类（如 `SendMessageUseCase`、`DecideApprovalUseCase`），避免巨型 Service。
- Agent Loop 拆成 `RunLifecycle`、`TurnExecutor`、`ToolCallCoordinator`、`ContextAssemblyService`。
- 事务只包住单次 entry 追加或状态更新；LLM 流与工具执行期间不持有数据库事务。
- 通过构造器注入 domain 端口；不出现 `@Mapper`、`ObjectMapper`、`HttpServletRequest`。

### 4.3 infrastructure

- 实现 domain/application 定义的端口。
- MyBatis PO 与领域对象分离；PO 只存在于 `infrastructure.persistence.po`。
- JSON payload 编解码集中在 `session.payload`，不让 Jackson 注解进入 domain。
- 插件 ClassLoader、内置工具、LLM HTTP 客户端、Caffeine 缓存都在这里。
- 对外提供 Spring `@Configuration`，由 app 扫描装配。

### 4.4 trigger

- REST Controller 只做参数绑定、调用用例、DTO 映射、HTTP 状态码。
- SSE 负责连接生命周期与事件序列化，不包含 agent 逻辑。
- CLI 通过 picocli 解析参数，调用同一应用层用例；不复制业务规则。
- 不注入 Mapper、不直接使用 MyBatis。

### 4.5 app

- 唯一 `@SpringBootApplication` 与组件扫描入口。
- Flyway 分厂商脚本、静态 Web UI、`application.yml`。
- ArchUnit 测试验证依赖方向；跨模块集成测试也放在 app。
- 不写业务代码；装配冲突在这里暴露得最清楚。

## 5. 关键运行形态

### 5.1 单进程

v0.1 是**单 JVM、单实例**部署：

- Run 注册表、SSE emitter、审批等待者、会话锁都在进程内；
- 会话一致性依赖“单写者 + 数据库乐观锁”，不依赖分布式锁；
- 多实例会导致 SSE 与审批等待不可达，因此明确不支持横向扩展。

### 5.2 双入口

```text
浏览器 ──HTTP/SSE──► trigger.web  ─┐
                                   ├──► application ──► domain
终端   ──picocli───► trigger.cli  ─┘          │
                                              └──► infrastructure ──► MySQL/H2、LLM、文件系统
```

Web 与 CLI 共享同一个 Spring 容器与用例，不维护两套 agent 逻辑。

### 5.3 Profile

| Profile | 数据库 | 模型 | 用途 |
|---|---|---|---|
| `demo`（默认） | H2 文件库 `./data/pi-java` | Fake | 本地开箱即用、演示、截图 |
| `test` | H2 内存 | Fake | 自动化测试 |
| `mysql` | MySQL 8 | 由模型档案决定 | 真实落地与 smoke |
| 真实模型 | 任意 | `DEEPSEEK_API_KEY` 等环境变量 | 真实编码任务 |

## 6. 数据所有权

| 数据 | 唯一事实源 | 缓存 |
|---|---|---|
| 会话与 entry | `pi_session_entry` | 不缓存 |
| 当前分支 | `pi_session.leaf_entry_id` | 不缓存 |
| 模型档案 | `pi_model_profile` | Caffeine 10 分钟 |
| 工具策略 | `pi_tool_policy` | Caffeine 2 分钟 |
| 审批待办 | `pi_approval_request`（投影） + `APPROVAL` entry（审计） | 不缓存 |
| 插件安装 | `pi_plugin` + 磁盘 jar | 启动快照 |
| Skill | 文件系统 | Caffeine 5 分钟 + mtime |
| Run 状态 | 进程内 `RunRegistry` | 不持久化，结束写 `RUN_SUMMARY` |

## 7. 架构约束与质量门

| 约束 | 执行方式 |
|---|---|
| 依赖方向 | ArchUnit `ModuleBoundaryTest` |
| 文件 ≤ 300 行、方法 ≤ 50 行、参数 ≤ 6 | Checkstyle `FileLength/MethodLength/ParameterNumber` |
| 一个文件一个顶层类型 | Checkstyle `OneTopLevelClass` |
| public 类型中文 Javadoc | Checkstyle `MissingJavadocType` |
| domain 覆盖率起步 60% | JaCoCo（M1 起接入门槛） |
| 离线可测 | Fake LLM、Fake 时钟、Fake ID、H2 |
| 密钥不落库 | 模型表只存环境变量名；日志脱敏 |

## 8. 包结构约定

```text
com.piagent.domain.<context>.
├── model/          聚合、实体、值对象、枚举
├── service/        无状态领域服务
└── port/           仓储与外部端口接口

com.piagent.application.<usecase>.
├── command/        输入 command/query
├── service/        用例编排
└── readmodel/      查询结果

com.piagent.infrastructure.<adapter>.
├── persistence/    mapper、po、repository 实现
├── llm/            provider 实现
├── tool/           内置工具
├── plugin/         加载器与运行时
└── config/         Spring 装配

com.piagent.trigger.web / trigger.cli
com.piagent.app
```

单个包超过 15 个类时按子领域拆分；聚合、领域服务、仓储、Mapper、PO 不混在同一包内。

## 9. 相关文档

- [领域模型](02-domain-model.md)
- [数据库设计](03-database-design.md)
- [运行时与事件](04-runtime-and-events.md)
- [API 与 CLI](05-api-and-cli.md)
- [插件 SDK](06-plugin-sdk.md)
- [测试与质量](07-testing-and-quality.md)
- [路线图](08-roadmap.md)
