# 14 部署、容器与 CI

> 状态：M15 引入（2026-10-26）。本文描述 v0.2.0 的构建产物、Docker 镜像、
> Compose 用法、运行参数与 GitHub Actions 门禁。

## 1. 构建产物

唯一可执行产物是 Spring Boot fat jar：

```bash
mvn -q -DskipTests package
# pi-java-app/target/pi-java-app-0.2.0.jar
```

同一个 jar：

- 不带参数：启动 Web 服务（默认 `demo` profile，H2 文件库 + Fake LLM）；
- 带子命令：执行 CLI（`run`、`chat`、`sessions`、`models`、`tools`、`serve`）。

默认数据库落在 `./data`，可用 `PI_DATA_DIR` 改到其它目录：

```bash
PI_DATA_DIR=/var/lib/pi-java java -jar pi-java-app-0.2.0.jar
```

服务默认只监听 `127.0.0.1:8080`。容器或内网访问需要显式设置
`PI_SERVER_ADDRESS=0.0.0.0`；v0.2 仍无鉴权，不要直接暴露公网。

## 2. Docker 镜像

`Dockerfile` 使用两阶段构建：

1. `maven:3.9-eclipse-temurin-21` 编译 `pi-java-app` 及其依赖模块，跳过测试；
2. `eclipse-temurin:21-jre` 运行，非 root 用户（uid 10001）、只带 JRE。

关键约定：

| 路径/变量 | 用途 |
|---|---|
| `/workspace` | 进程工作目录，也是代理的默认读写根；挂载宿主项目到这里 |
| `/app/data` | H2 数据目录（命名卷）；与工作区分离，避免把数据库写进用户仓库 |
| `/app/app.jar` | 可执行 jar |
| `PI_SERVER_ADDRESS=0.0.0.0` | 镜像内固定值，容器外仍需端口映射 |
| `PI_DATA_DIR=/app/data` | 镜像内固定值 |
| `SPRING_PROFILES_ACTIVE=demo` | 默认离线演示，可覆盖为 `mysql` |

`HEALTHCHECK` 每 30 秒访问 `/api/v1/system/info`，失败 3 次标记 unhealthy。

构建与运行：

```bash
docker build -t pi-java:0.2.0 .
docker run --rm -p 127.0.0.1:8080:8080 \
  -v "$PWD:/workspace" \
  -v pi-data:/app/data \
  pi-java:0.2.0
```

注意：

- 端口映射写死 `127.0.0.1`，与本地运行的安全默认值一致；
- Linux 上如果宿主目录对容器 uid 10001 不可写，代理的写工具会失败。可以
  `--user "$(id -u):$(id -g)"` 运行，或把宿主目录权限调整为可写；
- 容器内没有安装 git 以外的开发工具；`bash` 工具只能使用镜像内已有的命令。

## 3. Docker Compose

离线演示（H2 + 命名卷）：

```bash
docker compose up --build
```

默认把当前目录挂到 `/workspace`，可用 `PI_WORKSPACE` 指定其它项目：

```bash
PI_WORKSPACE=/path/to/project docker compose up --build
```

MySQL 变体（叠加文件，密码只从当前 shell 注入）：

```bash
PI_MYSQL_PASSWORD=your-password \
PI_MYSQL_ROOT_PASSWORD=your-root-password \
docker compose -f docker-compose.yml -f docker-compose.mysql.yml up --build
```

MySQL 首次启动需要初始化数据目录；应用会等待健康检查通过后再启动。
生产环境请自行准备备份、字符集与账号权限，不要把测试密码写进仓库。

## 4. OpenAPI 与调试页面

服务启动后：

- `GET /v3/api-docs`：OpenAPI 3 JSON 契约（由 springdoc 扫描 Controller 生成）；
- `GET /swagger-ui.html`：本地 Swagger UI，便于手工调用与查看 DTO。

`OpenApiDocumentIntegrationTest` 会校验文档端点、版本号与核心路径
（会话、计划、运行事件流、模型、工具）。接口重命名时测试先失败，避免发布过期契约。

文档只描述本地单机 API，不包含鉴权方案；请求与响应示例仍以
[05 API 与 CLI](05-api-and-cli.md) 为准。

## 5. GitHub Actions

`.github/workflows/ci.yml` 在 push 到 `main`、PR 与手动触发时运行三个作业：

| 作业 | 内容 |
|---|---|
| `verify` | JDK 21 + `mvn verify`（测试、Checkstyle、ArchUnit、JaCoCo、repackage） |
| `docker` | 构建镜像 → 启动容器 → 校验版本、OpenAPI 路径与首页 → 清理容器 |
| `mysql` | 启动 MySQL 8.4 服务 → 打包 → 以 `mysql` profile 启动 → 通过 HTTP 建会话并读回 |

失败时 `verify` 会上传 surefire/failsafe 报告，便于定位平台差异。

## 6. 已知限制

- 本地开发环境未安装 Docker，镜像构建与容器启动由 CI 的 `docker` 作业校验；
  本地只验证了 Dockerfile 语法与运行时参数（`PI_DATA_DIR`、监听地址、工作目录）；
- 不提供镜像仓库发布与自动打 tag；发布 tag 需用户确认；
- 单实例、单 JVM，不支持横向扩展；
- v0.2 无鉴权，容器端口默认只映射回环地址；
- 容器内没有完整的开发工具链，真实编码任务建议直接在本机运行 jar。
