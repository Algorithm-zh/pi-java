# M24 实施计划：移除本地账号与多空间系统

> 目标：pi-java 变成真正的本地单用户 agent。删除租户、用户、令牌、登录/注册/初始化、
> 空间与成员邀请、鉴权过滤器与前端账号页；保留会话、运行、审批、策略、附件、插件、
> 时间线、Web 聊天与 CLI。数据库通过 V9 迁移收敛到单用户结构。

**影响面**：76 个主代码文件、24 个后端测试文件、5 个控制器与约 20 个 DTO、
V6–V8 迁移、`auth.js` + `settings-account.js`、CI/Docker/启动脚本与 11 份文档。

**安全约束**：删除登录后，端口可达即等于可执行工具。服务仍默认绑定 `127.0.0.1`；
新增启动保护：非回环地址必须显式设置 `PI_ALLOW_REMOTE=true`，否则拒绝启动。

**工作区**：`.worktrees/m24-remove-auth`，分支 `feat/m24-remove-auth`，基线 `dba39d09`。

---

## Task 1：移除后端身份内核

**删除**
- `pi-java-domain/src/main/java/com/piagent/domain/identity/**`
- `pi-java-application/src/main/java/com/piagent/application/identity/**`
- `pi-java-infrastructure/src/main/java/com/piagent/infrastructure/persistence/identity/**`
- `pi-java-infrastructure/src/main/java/com/piagent/infrastructure/config/AuthConfiguration.java`
- `pi-java-trigger/src/main/java/com/piagent/trigger/http/auth/**`
- `pi-java-trigger/src/main/java/com/piagent/trigger/http/auth/IdentityAdminController.java`
- `DomainErrorCode` 中 AUTH_* / TENANT_* / USER_* 错误码
- `ApiExceptionHandler` 中对应映射

**收敛**
- `RunDispatcher`、`ToolCallCoordinator`：去掉 `AuthContext.wrap`
- `ApprovalController`：审批人从登录用户改为固定 `"local"`（或直接去掉人员字段，按现有
  payload 决定最小改动）
- `CliRunner`：去掉 `bindTenant` / `clear`
- `SessionTitleBackfillRunner`、`StaleRunStartupRunner`、`AttachmentGcJob`：去掉
  `TenantTaskRunner` 遍历，改为单次全量
- `SessionMapper` / `SessionEntryMapper` / `MyBatisSessionRepository`：删 `tenant_id`
  条件与 `findAllPayloadsByTenant` 的 JOIN
- `ToolPolicyMapper` / `ToolPolicyPO` / `MyBatisToolPolicyRepository`：删 `tenant_id`
- `ApprovalMapper`：删 `pi_session.tenant_id` 子查询
- 附件：`FileSystemAttachmentStore` 用单一根目录；删除 `TenantAttachmentScope`；
  `SessionEntryAttachmentReferenceSource` 去掉租户参数
- `application.yml`：删 `pi.auth.*`

**新增**
- `GET /api/v1/health`：无鉴权，返回 `{"status":"ok"}`，供 CI / Docker 健康检查
- 启动保护：`server.address` 非回环时要求 `PI_ALLOW_REMOTE=true`

**迁移 V9（h2 + mysql）**
1. 按 `(scope, workspace_key, tool_name)` 去重 `pi_tool_policy`，保留最早一条
2. 删除 policy 的租户外键、唯一键、索引、`tenant_id` 列，重建三列唯一键
3. 删除 session 的租户外键、索引、`tenant_id` 列
4. 依次删除 `pi_space_member`、`pi_auth_token`、`pi_app_user`、`pi_tenant`

**验证**：`mvn -q -DskipTests compile`

## Task 2：清理测试与夹具

- 删除身份相关单测：`AuthContextTest`、`AuthApplicationServiceTest`、
  `IdentityAdminServiceTest`、`AuthControllerTest`、`AuthFilterTest`、
  `IdentityAdminControllerTest`、`AuthHttpIntegrationTest`、
  `TenantIsolationIntegrationTest`、`InMemory*`（Tenant/User/Membership/Token）
- `AuthenticatedMockMvcConfig` 改为普通 MockMvc 配置（或删除并让测试直接用
  `@AutoConfigureMockMvc`）；更新 7 个 import 它的集成测试
- `OpenApiDocumentIntegrationTest`：移除 `/auth/*` 断言，补 `/health`
- 审批、会话、附件、策略测试：去掉租户/用户构造与 tenant_id
- 验证：`mvn -q test`

## Task 3：Web 去除登录与账号界面

- `index.html`：删除 `#auth-gate` 整段、侧栏 `#account-card`、设置里的“账号与空间”页
- 删除 `js/auth.js`、`js/settings-account.js`
- `app.js`：启动直接 `startApp()`，不再等待 `ensureSession`
- `api.js`：删除 auth/tenant/user/member 端点与 401 事件
- `store.js` / `settings.js`：移除账号卡片与账号页
- `app.css` / `settings.css`：移除 auth gate、账号卡片与账号列表样式
- 验证：启动应用，浏览器直接进入聊天；新建会话、发消息、审批、时间线正常

## Task 4：CI、Docker 与启动脚本

- `Dockerfile` HEALTHCHECK 改 `/api/v1/health`
- `.github/workflows/ci.yml` 两处探活与两段 smoke：删除 setup/login/Bearer，直接调用业务接口
- `scripts/start.ps1`：`$ReadyPath` 改 health；删除 `PI_AUTH_TOKEN`、登录/初始化分支
- `docker-compose.yml` 注释更新

## Task 5：文档

- 更新 `docs/05`、`09`、`03`、`07`、`14`、`00`、`01`、`docs/README.md`
- `docs/08-roadmap.md`：M24 改为“去账号化”，M25 分支切回
- `docs/milestones/M19.md`、`M20.md`、`M21.md` 顶部加“已于 M24 移除”
- 根 `README.md`、`pi-java-app/README.md`：删除多用户/登录描述
- 设计文档 `2026-11-06-branch-resume-design.md` 里程碑编号顺延为 M25

## Task 6：全量验证

- `mvn -q test` 全绿
- 停掉 8080 后 `mvn -q verify`（需用户确认），再启动实测
- Web：初始化直达、会话、审批、时间线、切换（M25 前不涉及）
- CLI：`chat` 可直接使用，无租户上下文

## Task 7：本地提交

按 Task 分块提交：后端内核、测试、Web、CI/脚本、文档。不推送、不打 tag。

---

## 验收

- 全新数据库启动后没有登录页，直接进入聊天
- 没有任何 `/api/v1/auth`、`/tenants`、`/users`、`/members` 路由
- V9 迁移在 H2 与 MySQL 上均可从 V8 升级，旧会话与策略保留、策略冲突已去重
- 非回环绑定且未设置 `PI_ALLOW_REMOTE=true` 时启动失败并给出明确提示
- `mvn -q test`、`mvn -q verify` 全绿；Checkstyle/ArchUnit 通过
