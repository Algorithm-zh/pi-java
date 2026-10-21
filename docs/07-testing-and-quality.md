# 07 测试与质量

> 状态：M0 冻结。每个里程碑必须通过本文定义的统一门禁，才能提交与进入下一阶段。

## 1. 质量目标

| 目标 | 度量 | 门禁 |
|---|---|---|
| 领域规则可离线验证 | domain 单测，无 Spring/DB | `mvn verify` |
| 模块边界不腐化 | ArchUnit 规则 | 全绿 |
| 核心链路可回归 | Fake LLM + H2 端到端 | 全绿 |
| 真实协议不靠猜 | WireMock 模拟 OpenAI-compatible SSE | 全绿 |
| 代码可维护 | 文件 ≤ 300 行、方法 ≤ 50 行、参数 ≤ 6 | Checkstyle |
| 文档与代码同步 | 每个里程碑更新 docs 与 README | 人工检查 |
| 覆盖率不倒退 | domain/application 行覆盖 ≥ 60% 起步 | JaCoCo |

## 2. 测试金字塔

```text
                 ┌──────────────────────┐
                 │ 浏览器手工走查 / E2E  │  少量，验收用
                 ├──────────────────────┤
                 │ 应用层集成测试（H2）  │  每个用例一条主路径
                 ├──────────────────────┤
                 │ 适配器测试（WireMock）│  流式、重试、取消
                 ├──────────────────────┤
                 │ 领域单元测试          │  大量、快速、无 IO
                 └──────────────────────┘
```

原则：

- 领域逻辑不靠集成测试兜底；不变量必须在 domain 测试中覆盖；
- 数据库相关规则（唯一键、CAS、级联）用 H2 集成测试验证；
- HTTP/SSE 解析用 WireMock，不访问真实模型；
- 浏览器手工走查只验证交互，不替代自动化测试；
- 测试失败时先复现再修，不允许通过降低断言或跳过测试“修复”。

## 3. 各模块测试策略

| 模块 | 测试类型 | 关键内容 |
|---|---|---|
| domain | JUnit 5 + AssertJ | ULID 排序/解析、entry 校验、投影、分支策略、压缩切点、消息配对、路径判定、审批状态机、子代理权限 |
| plugin-api | 编译期检查 + ArchUnit | 只依赖 JDK；示例插件独立编译 |
| application | JUnit + Mockito | 用例编排、状态机、取消传播、重试边界、事件顺序 |
| infrastructure | H2 集成、WireMock、临时目录 | MyBatis 映射、Flyway、LLM SSE 解析、工具路径/超时、ClassLoader 卸载 |
| trigger | MockMvc + 单元测试 | DTO 校验、错误映射、SSE 格式、CLI 参数解析 |
| app | `@SpringBootTest` | 装配、Flyway、健康检查、ArchUnit、E2E |

## 4. 领域测试清单

### 4.1 会话

- 空会话 → 追加首条 entry：`parentId=null`、`seq=1`、leaf 指向新 entry；
- 追加第二条：`parentId=leaf`、`seq=2`；
- 分支：回到旧节点后追加，旧分支保留、leaf 移动、seq 继续递增；
- fork：root/parent/depth/origin 正确；不允许 fork 到别的会话；
- 投影：分支只包含祖先链；压缩后只保留摘要 + 尾部；
- 工具配对：toolResult 无 toolCall 时报错；toolCall 缺结果时标记悬空；
- 切点：压缩切点落在 toolResult 时向前回退到合法节点。

### 4.2 工具与治理

- 未知工具默认 DENY；
- 只读工具默认 ALLOW；写工具默认 ASK；`bash` 默认 CRITICAL；
- 策略优先级：WORKSPACE > USER > 默认，DENY 不被放宽；
- 审批：PENDING→APPROVED/DENIED/EXPIRED/CANCELLED，终态不可逆；
- 路径：`..` 逃逸、绝对路径越界、盘符切换、符号链接逃逸被拒绝；
- 参数：`args_digest` 对键排序稳定，脱敏后不含密钥；
- 工具超时/取消生成 `isError=true` 结果。

### 4.3 运行时

- 同一 session 并发发起两个 run：第二个返回 409；
- LLM 失败发生在首个 delta 之前：adapter 重试 ≤ 2；
- 已输出 delta 后失败：不重试，状态 FAILED；
- 取消：LLM/工具/审批/子代理全部收到取消，最终 ABORTED；
- 预算：maxTurns 达到时停止并写 MAX_STEPS；
- run 结束必写 RUN_SUMMARY，且只写一次；
- 启动恢复：悬空 toolCall 补结果、无 run 的 RUNNING 会话补摘要。

### 4.4 压缩与 Skills

- 触发阈值：`tokens > window - reserve`；
- 保留最近 `keepRecentTokens`，不切 toolResult；
- split-turn：单个超长 turn 生成前缀摘要；
- 摘要不收敛时降级为剪枝，run 仍能继续；
- Skill frontmatter 解析：name/description 必填，非法 name 警告但加载；
- 目录 mtime 改变后缓存失效；`/skill:name` 注入正文。

### 4.5 子代理

- 深度上限：达到后子工具目录不含 `subagent`；
- 工具子集：请求白名单不能超出父快照；
- 审批不继承：父已允许的 write 在子会话仍需审批；
- 取消级联：父取消 → 子 ABORTED；
- 结果：`isError = stopReason != STOP`，带 childSessionId/usage。

## 5. LLM 适配器测试

### 5.1 FakeLlmProvider

确定性脚本由测试提供：

```java
FakeScript.builder()
    .turn(t -> t.text("先读取文件").toolCall("read", Map.of("path", "pom.xml")))
    .turn(t -> t.text("依赖如下...").stop())
    .build();
```

要求：

- 支持文本增量、thinking 增量、toolCall、错误、取消；
- 每个 delta 可配置延迟（虚拟时间优先，避免真实 sleep）；
- 相同脚本必得相同事件序列，便于断言 SSE；
- 所有离线 E2E 默认使用 Fake，不读取 API Key。

### 5.2 OpenAI-compatible

WireMock 覆盖：

| 场景 | 断言 |
|---|---|
| 正常 SSE | 逐块解析、toolCall 拼接、usage 提取 |
| 429 后成功 | 重试 2 次内恢复，事件不重复 |
| 500 持续 | 重试后 FAILED，错误信息脱敏 |
| 首个 delta 后断流 | 不重试，已输出内容保留 |
| 取消 | HTTP 连接关闭，线程退出，不泄漏 |
| 畸形 JSON | 明确错误，不吞异常 |
| context_length_exceeded | 触发一次压缩后重试 |

不引入 Spring AI / LangChain4j；HTTP 使用 JDK `HttpClient`，SSE 解析手写在 adapter 内。

## 6. 数据库集成测试

- H2 内存库，`MODE=MySQL`；每次测试类前执行 Flyway，测试间清理业务表；
- 不使用 `schema.sql`；迁移脚本是唯一建表方式；
- 覆盖：
  - `(session_id, seq)` 唯一冲突；
  - `version` CAS 失败回滚；
  - session 删除级联 entry/approval；
  - parent_session `SET NULL`；
  - 审批唯一键 `(run_id, tool_call_id)`；
  - 时间精度毫秒、UTF-8 中文往返；
- MySQL 方言不靠 H2 完全替代：M9 增加本机 MySQL smoke（建库 → Flyway → 启动 → 一条会话）。

## 7. 架构与静态检查

### 7.1 ArchUnit

`ModuleBoundaryTest` 强制：

- domain 只依赖 JDK；
- application 不依赖 Web/ORM/Jackson/plugin-api/infrastructure/trigger；
- plugin-api 只依赖 JDK；
- trigger 不依赖 infrastructure/MyBatis；
- infrastructure 不依赖 trigger。

新增模块或包时先补规则，再写代码。

### 7.2 Checkstyle

主代码（不含测试）在 `verify` 阶段检查：

- 文件 ≤ 300 行；
- 方法 ≤ 50 行；
- 参数 ≤ 6；
- 一个顶层类型；
- public 类型必须有 Javadoc；
- 禁止 tab、星号导入、未使用导入；
- 行宽 ≤ 140。

局部豁免必须写 `@SuppressWarnings("checkstyle:XXX")` 并在提交说明中解释。

### 7.3 JaCoCo

- M1 起对 `pi-java-domain`、`pi-java-application` 设置 60% 行覆盖门槛；
- 后续里程碑不得降低已达成覆盖率；
- 生成 HTML 报告到 `target/site/jacoco`；
- 排除纯 record/DTO 需谨慎：优先补行为测试，不靠排除刷数字。

M1 实测（2026-10-21）：

| 模块 | 行覆盖 | 门槛 |
|---|---|---|
| `pi-java-domain` | 89.4% | ≥ 60% |
| `pi-java-application` | 98.9% | ≥ 60% |

application 的用例编排由内存端口单测覆盖，真实 SQL 行为由 app 模块的 H2 集成测试覆盖，
避免为了覆盖率把基础设施依赖倒灌进应用层。

## 7.4 测试环境

- `pi-java-app/src/test/resources/application-test.yml` 提供内存 H2（MySQL 模式）；
- 集成测试使用 `@ActiveProfiles("test")`，Flyway 扫描 `db/migration/h2`；
- 测试类之间用 SQL 清空 `pi_session_entry` 与 `pi_session`，不依赖执行顺序；
- 数据库连接串带 `DATABASE_TO_LOWER=TRUE` 与 `CASE_INSENSITIVE_IDENTIFIERS=TRUE`，与 demo 环境一致。

## 8. 端到端测试

### 8.1 demo E2E（M4 起）

1. 启动 `demo` profile（H2 + Fake）；
2. 创建会话；
3. POST 消息并读取 SSE；
4. 断言文本增量、工具卡片事件、run.completed；
5. 触发一次 read 工具与一次审批写文件；
6. 取消一次运行，断言 ABORTED；
7. 查看 entry 树与分支。

### 8.2 黄金样本（M7）

从 pi 的真实 JSONL 会话中提取脱敏样本，验证：

- 树形 entry 解析；
- 压缩后投影结果；
- 工具调用/结果配对；
- 未知字段向前兼容。

黄金样本放 `pi-java-domain/src/test/resources/golden/`，只读，不修改原始文件。

## 9. Windows 与跨平台

- 路径测试用 `@EnabledOnOs` 区分 Windows/Unix；
- Windows 盘符、反斜杠、大小写不敏感、符号链接权限需单独用例；
- `bash` 工具在 Windows 使用 `cmd.exe /c` 或配置的 shell；测试覆盖超时与子进程树清理；
- 文件锁、临时目录清理使用 `@TempDir`；
- CI 若只跑 Linux，Windows 行为由本机手工 smoke 兜底，文档明确。

## 10. 提交前检查清单

```bash
mvn -q verify                 # 单测 + 集成 + 静态检查
git status                    # 确认没有 target/.env/密钥
git diff --stat               # 确认没有巨文件
```

- [ ] 新行为有测试，且测试在无网络环境可跑；
- [ ] 没有硬编码密钥、token、个人路径；
- [ ] 文档与 API/DDL 同步；
- [ ] 模块依赖方向未破坏；
- [ ] 失败路径（超时、取消、拒绝）有覆盖；
- [ ] 提交信息符合 Conventional Commits。

## 11. 已知取舍

- v0.1 不做 Testcontainers：本机 Docker 不可用，MySQL 用本机 smoke 补充；
- 不做变异测试、契约测试平台、性能基准；
- SSE 前端自动化暂用 MockMvc + 手工浏览器走查，M9 再评估 Playwright；
- 覆盖率门槛只覆盖 domain/application，基础设施靠行为测试而非数字。

## 12. 相关文档

- [整体架构](01-architecture.md)
- [路线图](08-roadmap.md)
