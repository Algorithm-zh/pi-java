# pi-java

pi-java 是一个用 **Java 21** 重写的、**DDD 分层**、**插件化**的 coding agent。
它参考 pi 的极简 harness 思路（树形会话日志、扩展、Skills、上下文压缩、内置编码工具），
并吸收 DeepSeek Harness 的工程经验（日志即事实源、审批管线、失败处理、受限子代理）。

> 当前状态：**M3 只读工具与路径沙箱已完成**。已具备树形会话日志、模型档案与流式
> LLM Gateway（Fake + OpenAI-compatible）、`read/ls/grep/glob` 只读工具、路径沙箱与
> 工具策略表。下一步 M4：Agent Loop + 首个 Web demo。
> 每个里程碑都包含实现、测试、文档与 git 提交。

## 核心设计

- **会话日志是唯一事实源**：消息、压缩摘要、审批决策、运行摘要都是 append-only entry，
  分支只移动 leaf，不复制历史。
- **领域纯净**：`domain` 只依赖 JDK；Spring、Jackson、MyBatis、插件加载都在外层。
- **安全默认**：只读工具放行，写工具询问，未知工具拒绝；路径沙箱 + 人工审批。
- **插件化**：零依赖 `plugin-api`，每个插件独立 ClassLoader，工具带插件前缀。
- **单机强一致**：一个 session 同时只有一个活跃 run；不引入 Redis。
- **可测试**：Fake LLM、Fake 时钟、H2、WireMock，离线可跑完整链路。

## 模块

| 模块 | 职责 |
|---|---|
| `pi-java-domain` | 纯领域：7 个限界上下文、聚合、值对象、领域服务、仓储/端口 |
| `pi-java-application` | 用例编排、Agent Loop、事务边界、读模型 |
| `pi-java-plugin-api` | 零依赖的第三方插件契约 |
| `pi-java-infrastructure` | LLM 接入、MyBatis 仓储、内置工具、插件加载、缓存 |
| `pi-java-trigger` | HTTP/SSE、CLI（picocli） |
| `pi-java-app` | Spring Boot 装配、Flyway、Web UI、架构测试 |
| `examples/pi-plugin-example` | 示例插件，验证契约纯度 |

依赖方向：`app -> trigger/infrastructure`，`trigger -> application`，
`infrastructure -> application/domain/plugin-api`，`application -> domain`。

## 构建

需要 JDK 21 与 Maven 3.9+。

```bash
mvn -q verify
```

当前 M3 已验证通过：239 个测试全绿，Checkstyle/ArchUnit 通过，
domain 行覆盖 86.7%、application 行覆盖 98.0%。

## 文档

完整文档见 [docs/README.md](docs/README.md)。

- [愿景与范围](docs/00-vision-and-scope.md)
- [整体架构](docs/01-architecture.md)
- [领域模型](docs/02-domain-model.md)
- [数据库设计（6 张表完整 DDL）](docs/03-database-design.md)
- [运行时与事件](docs/04-runtime-and-events.md)
- [API 与 CLI](docs/05-api-and-cli.md)
- [插件 SDK](docs/06-plugin-sdk.md)
- [测试与质量](docs/07-testing-and-quality.md)
- [路线图](docs/08-roadmap.md)
- [M0 里程碑记录](docs/milestones/M0.md)
- [M1 里程碑记录](docs/milestones/M1.md)
- [M2 里程碑记录](docs/milestones/M2.md)
- [M3 里程碑记录](docs/milestones/M3.md)

架构决策：

- [ADR 0001：会话日志是唯一事实源](docs/adr/0001-session-log-as-source-of-truth.md)
- [ADR 0002：不引入 Redis，只使用本地 Caffeine](docs/adr/0002-no-redis-local-cache.md)
- [ADR 0003：插件 API 零依赖](docs/adr/0003-plugin-api-zero-dependency.md)
- [ADR 0004：手写 LLM Gateway](docs/adr/0004-hand-rolled-llm-gateway.md)

## 安全

- v0.1 默认只监听 `127.0.0.1`，无鉴权，禁止直接暴露公网；
- 模型密钥只从环境变量读取，数据库只保存环境变量名；
- 插件类加载隔离不是安全沙箱，只安装可信来源；
- `bash` 无法做路径级沙箱，只能限制 cwd、审批、超时与输出，文档与 UI 会明确提示。

## 开发约定

- 主代码文件 ≤ 300 行，方法 ≤ 50 行，参数 ≤ 6；一个文件一个顶层类型；
- public 类型必须有中文 Javadoc，核心算法解释“为什么”与不变量；
- 每个里程碑：实现 → 测试 → 实际运行 → 更新文档 → 单独提交；
- 不主动 push，发布 tag 需明确确认。
