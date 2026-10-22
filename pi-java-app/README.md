# pi-java-app

唯一装配点与启动模块。

## 职责

- `PiJavaApplication`：Spring Boot 启动、组件扫描 `com.piagent`；
- `application.yml`：demo / mysql profile，密钥只来自环境变量；
- Flyway 分厂商迁移目录：`db/migration/h2` 与 `db/migration/mysql`；
- 静态 Web UI：`src/main/resources/static/`；
- 跨模块集成测试与 ArchUnit 架构测试。

## 启动

```bash
mvn -q -DskipTests package
java -jar pi-java-app/target/pi-java-app-0.1.0-SNAPSHOT.jar
```

开发期可用 `mvn -q -pl pi-java-app spring-boot:run`（需先执行一次
`mvn -q -DskipTests install`，让依赖模块进入本地仓库）。

默认 `demo` profile：H2 文件库 + Fake LLM，无需 API Key。
MySQL 使用 `--spring.profiles.active=mysql`，连接信息通过 `PI_MYSQL_*` 环境变量注入。

默认只监听 `127.0.0.1`；v0.1 无鉴权，禁止直接暴露公网。

## 测试

- `ModuleBoundaryTest`：模块依赖方向；
- `SessionPersistenceIntegrationTest`：H2 上的仓储、唯一键、CAS、级联；
- `SessionApplicationIntegrationTest`：创建 → 追加 → 投影 → fork 的端到端路径；
- `AgentLoopIntegrationTest`：Fake 模型 + 真实工具 + H2 的完整 run；
- `RunSseIntegrationTest`：真实 Spring MVC 异步链路的 SSE 事件顺序与 `Last-Event-ID` 回放；
- `PluginHttpIntegrationTest`：动态生成插件 jar，验证安装/启用/停用与 404/409/422；
- `PluginEndToEndIntegrationTest`：插件工具经 Fake 模型 `/tool` 调用的完整 SSE 链路。

测试 profile 位于 `src/test/resources/application-test.yml`，使用内存 H2 与
`db/migration/h2`；测试之间直接清理业务表，保证可重复执行。

## 当前状态

M1 完成启动类、demo/mysql/test 配置、ArchUnit、Flyway V1 与会话集成测试；
M2 增加 V2 迁移与模型档案 H2 往返/CAS 集成测试；M3 增加 V3 策略表与
工具策略往返/工作区查询/CAS 集成测试；M4 增加 Agent Loop 集成测试、SSE 集成测试
与只读 Web UI（`src/main/resources/static/`）；M5 增加 V4、`PluginStartupRunner`
启动恢复、插件 HTTP 与端到端集成测试，以及动态插件 jar 测试夹具。共 39 个测试。
详见 [M5 记录](../docs/milestones/M5.md)。
