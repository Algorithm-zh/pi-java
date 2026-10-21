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
mvn -q -pl pi-java-app -am spring-boot:run
# 或
mvn -q -DskipTests package
java -jar pi-java-app/target/pi-java-app-0.1.0-SNAPSHOT.jar
```

默认 `demo` profile：H2 文件库 + Fake LLM，无需 API Key。
MySQL 使用 `--spring.profiles.active=mysql`，连接信息通过 `PI_MYSQL_*` 环境变量注入。

默认只监听 `127.0.0.1`；v0.1 无鉴权，禁止直接暴露公网。

## 测试

- `ModuleBoundaryTest`：模块依赖方向；
- `SessionPersistenceIntegrationTest`：H2 上的仓储、唯一键、CAS、级联；
- `SessionApplicationIntegrationTest`：创建 → 追加 → 投影 → fork 的端到端路径；
- M4 起加入 MockMvc/SSE 集成测试与 demo E2E。

测试 profile 位于 `src/test/resources/application-test.yml`，使用内存 H2 与
`db/migration/h2`；测试之间直接清理业务表，保证可重复执行。

## 当前状态

M1：启动类、demo/mysql/test 配置、ArchUnit、Flyway V1 与会话集成测试已完成。
Web UI 从 M4 开始。
