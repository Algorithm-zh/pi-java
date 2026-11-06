# M23 分叉对比实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task.
> Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 回退之后，把被放弃的分支与当前分支投影成一份只读对比，并在 Web 与 CLI 提供入口。

**Architecture:** 应用层新增纯投影器 `BranchComparisonProjector`，服务
`SessionBranchComparisonService` 负责选回退点、加载两条分支、求分叉点并生成摘要；
触发层只做 DTO 映射；Web/CLI 只消费服务端结论。全程只读，不新增迁移。

**Tech Stack:** Java 21、Spring Boot、JUnit 5 + AssertJ + MockMvc、原生 ES Modules、JLine。

---

## 文件结构

| 文件 | 职责 |
|---|---|
| `pi-java-application/.../timeline/SessionBranchComparison.java` | 对比模型（record） |
| `pi-java-application/.../timeline/BranchComparisonProjector.java` | 单侧投影、文件差异、确定性摘要 |
| `pi-java-application/.../timeline/SessionBranchComparisonService.java` | 回退选择、分叉点、编排 |
| `pi-java-application/.../timeline/SessionTimeline.java` | REWIND 节点增加 `rewindEntryId` |
| `pi-java-trigger/.../http/session/SessionComparisonController.java` | GET 对比接口 |
| `pi-java-trigger/.../http/session/SessionComparisonResponse.java` | 对比响应 DTO |
| `pi-java-trigger/.../cli/CliGitSupport.java` | `/diff` `/undo` 迁移到这里 |
| `pi-java-trigger/.../cli/CliTimelineSupport.java` | `/timeline` `/compare` `/rewind` |
| `pi-java-app/.../static/js/timeline.js` | REWIND 按钮、对比对话框渲染 |
| `pi-java-app/.../static/js/api.js` | `sessionComparison` |
| `pi-java-app/.../static/index.html` + `css/timeline.css` | 对话框与样式 |
| `pi-java-app/src/test/.../BranchComparisonIntegrationTest.java` | 端到端 |

---

### Task 1: 对比模型与投影器

**Files:**
- Create: `pi-java-application/src/main/java/com/piagent/application/timeline/SessionBranchComparison.java`
- Create: `pi-java-application/src/main/java/com/piagent/application/timeline/BranchComparisonProjector.java`
- Test: `pi-java-application/src/test/java/com/piagent/application/timeline/BranchComparisonProjectorTest.java`

- [ ] **Step 1: 写失败测试**

覆盖：
1. 单侧投影：两段用户消息、一个检查点、一条 `RUN_SUMMARY`（turns/toolCalls）、拒绝审批、
   失败验证、计划快照、两轮文件改动（同名文件合并行数）。
2. 列表上限：25 条意图只留 20；12 个拒绝工具名只留 10。
3. 文件差异：`onlyAbandoned` / `onlyCurrent` / `both` 排序与截断。
4. 摘要：包含两侧轮数、运行数、拒绝/失败数与独有文件；长度不超过 500。

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn -q -pl pi-java-application -am -Dtest=BranchComparisonProjectorTest -Dsurefire.failIfNoSpecifiedTests=false test
```

- [ ] **Step 3: 实现模型**

```java
public record SessionBranchComparison(boolean available, EntryId rewindEntryId,
        EntryId forkEntryId, Instant at, BranchSide abandoned, BranchSide current,
        FileDifference files, String summary) {
    public static SessionBranchComparison unavailable() { ... }
    public record BranchSide(EntryId leafEntryId, List<String> intents, int runs,
            int completedRuns, int turns, int toolCalls, List<String> stopReasons,
            ApprovalStats approvals, VerificationStats verifications, PlanStats plan,
            ChangeStats changes, List<FileSummary> files) { compact copy lists }
    public record ApprovalStats(int total, int denied, List<String> deniedTools) {}
    public record VerificationStats(int total, int failed, List<String> failedCommands) {}
    public record PlanStats(int total, int done, List<String> unfinished) {}
    public record ChangeStats(int turns, int files, int additions, int deletions) {}
    public record FileSummary(String path, int additions, int deletions, int changes) {}
    public record FileDifference(List<String> onlyAbandoned, List<String> onlyCurrent,
            List<String> both, boolean truncated) {}
}
```

- [ ] **Step 4: 实现投影器**

常量：`MAX_INTENTS = 20`、`MAX_INTENT_CHARS = 400`、`MAX_LIST = 10`、
`MAX_FILES = 100`、`MAX_SUMMARY = 500`。

`project(List<SessionEntry> segment, EntryId leafEntryId)`：
- 跳过 `BranchSummaryPayload`（回退简报不计数）；
- `MessagePayload + UserMessage` → 意图（只取 `TextBlock`，空内容记“（图片消息）”），截断 400；
- `GitCheckpointPayload` → `runs++`；
- `RunSummaryPayload` → `completedRuns++`、累加 turns/toolCalls、收集去重 stopReasons；
- `ApprovalPayload`：`decision` 忽略大小写等于 `DENIED` 计拒绝并收集工具名；
- `VerificationPayload`：`!passed()` 计失败并收集命令；
- `PlanPayload`：整份快照，取最后一份；
- `GitChangeSetPayload`：按路径合并 additions/deletions，累计轮数；`changes` 统计不同路径数。

`difference(BranchSide abandoned, BranchSide current)` 用路径集合求交，排序后各取 100。

`summary(...)` 拼接固定中文，超长截断并补“…”；空侧输出“没有后续动作”。

- [ ] **Step 5: 运行测试通过并提交**

```bash
mvn -q -pl pi-java-application -am -Dtest=BranchComparisonProjectorTest -Dsurefire.failIfNoSpecifiedTests=false test
git add pi-java-application/src/main/java/com/piagent/application/timeline/SessionBranchComparison.java \
        pi-java-application/src/main/java/com/piagent/application/timeline/BranchComparisonProjector.java \
        pi-java-application/src/test/java/com/piagent/application/timeline/BranchComparisonProjectorTest.java
git commit -m "feat(m23): add branch comparison projection"
```

---

### Task 2: 对比服务与分叉点

**Files:**
- Create: `pi-java-application/src/main/java/com/piagent/application/timeline/SessionBranchComparisonService.java`
- Test: `pi-java-application/src/test/java/com/piagent/application/timeline/SessionBranchComparisonServiceTest.java`

- [ ] **Step 1: 写失败测试**

使用 `InMemorySessionRepository`、`InMemorySessionEntryStore`、`SequentialIdGenerator`，
逐条 `append` 并 `moveLeaf`。覆盖：
1. 无回退 → `available=false`；
2. 回退后继续对话 → 两侧意图、拒绝、验证、文件分类正确；
3. 嵌套回退默认取最近一次；
4. 指定不在当前分支的简报 → `INVALID_BRANCH`；
5. 回退到第一轮之前（从 root 分叉）→ 旧侧为空仍可用。

- [ ] **Step 2: 运行确认失败**

```bash
mvn -q -pl pi-java-application -am -Dtest=SessionBranchComparisonServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

- [ ] **Step 3: 实现服务**

```java
@Service
public class SessionBranchComparisonService {
    public SessionBranchComparison compare(SessionId sessionId, EntryId rewindEntryId) {
        ChatSession session = require(sessionId);
        List<SessionEntry> current = entries.loadBranch(sessionId, session.leafEntryId());
        List<SessionEntry> rewindBriefs = current.stream()
                .filter(e -> e.payload() instanceof BranchSummaryPayload s && isRewind(s))
                .toList();
        if (rewindBriefs.isEmpty()) return SessionBranchComparison.unavailable();
        SessionEntry brief = select(rewindBriefs, rewindEntryId); // 指定不存在 → INVALID_BRANCH
        EntryId from = ((BranchSummaryPayload) brief.payload()).fromEntryId();
        List<SessionEntry> abandoned = entries.loadBranch(sessionId, from);
        int fork = commonPrefix(abandoned, current);
        BranchSide abandonedSide = projector.project(abandoned.subList(fork, abandoned.size()), from);
        BranchSide currentSide = projector.project(current.subList(fork, current.size()),
                session.leafEntryId());
        EntryId forkEntryId = fork == 0 ? null : current.get(fork - 1).id();
        return new SessionBranchComparison(true, brief.id(), forkEntryId, brief.createdAt(),
                abandonedSide, currentSide, projector.difference(abandonedSide, currentSide),
                projector.summary(abandonedSide, currentSide, files));
    }
}
```

- [ ] **Step 4: 通过并提交**

```bash
mvn -q -pl pi-java-application -am -Dtest='SessionBranchComparison*Test' -Dsurefire.failIfNoSpecifiedTests=false test
git add pi-java-application/src/main/java/com/piagent/application/timeline/SessionBranchComparisonService.java \
        pi-java-application/src/test/java/com/piagent/application/timeline/SessionBranchComparisonServiceTest.java
git commit -m "feat(m23): compare abandoned and current branches"
```

---

### Task 3: 时间线补 rewindEntryId

**Files:**
- Modify: `pi-java-application/.../timeline/SessionTimeline.java`
- Modify: `pi-java-application/.../timeline/SessionTimelineService.java`
- Modify: `pi-java-trigger/.../http/session/SessionTimelineResponse.java`
- Test: `SessionTimelineServiceTest`、`SessionTimelineControllerTest`

- [ ] **Step 1: 先改测试断言 REWIND 节点带 `rewindEntryId`，运行确认失败。**
- [ ] **Step 2: `RewindEvidence` 增加 `EntryId entryId` 并传入 `entry.id()`；`NodeResponse` 增加 `String rewindEntryId`（RUN 为 null）。**
- [ ] **Step 3: 跑两个测试类，通过后提交。**

```bash
git commit -m "feat(m23): expose rewind entry id on timeline nodes"
```

---

### Task 4: 对比 HTTP 接口

**Files:**
- Create: `pi-java-trigger/.../http/session/SessionComparisonController.java`
- Create: `pi-java-trigger/.../http/session/SessionComparisonResponse.java`
- Test: `pi-java-trigger/src/test/java/com/piagent/trigger/http/session/SessionComparisonControllerTest.java`

- [ ] **Step 1: 写 MockMvc 测试**

`GET /api/v1/sessions/{id}/comparison`（无参、带 `rewind`）断言：
`available`、`rewindEntryId`、`forkEntryId`、`at`、两侧计数、文件分组、`summary`；
服务抛 `INVALID_BRANCH` → 409；无回退 → 200 且 `available=false`。

- [ ] **Step 2: 实现 DTO 与控制器**

```java
@GetMapping("/{id}/comparison")
public SessionComparisonResponse comparison(@PathVariable String id,
        @RequestParam(required = false) String rewind) {
    EntryId rewindId = rewind == null || rewind.isBlank() ? null : EntryId.parse(rewind);
    return SessionComparisonResponse.from(comparisons.compare(SessionId.parse(id), rewindId));
}
```

DTO 字段与模型一一对应；`EntryId` 一律转字符串。

- [ ] **Step 3: 测试通过并提交**

```bash
git commit -m "feat(m23): add branch comparison endpoint"
```

---

### Task 5: Web 对比对话框

**Files:**
- Modify: `pi-java-app/src/main/resources/static/js/api.js`
- Modify: `pi-java-app/src/main/resources/static/js/store.js`
- Modify: `pi-java-app/src/main/resources/static/js/timeline.js`
- Modify: `pi-java-app/src/main/resources/static/index.html`
- Modify: `pi-java-app/src/main/resources/static/css/timeline.css`

- [ ] **Step 1: API 助手**

```js
export function sessionComparison(sessionId, rewindEntryId) {
    const query = rewindEntryId
        ? `?rewind=${encodeURIComponent(rewindEntryId)}` : "";
    return request(`/api/v1/sessions/${encodeURIComponent(sessionId)}/comparison${query}`);
}
```

- [ ] **Step 2: 对话框标记**

`<dialog id="compare-dialog" class="timeline-preview compare-dialog">`，含：
标题、`#compare-summary`、`.compare-grid` 两列（`#compare-abandoned`、
`#compare-current`）、文件差异区 `#compare-files`、关闭按钮。全部静态结构，
动态文本只经 `textContent`。

- [ ] **Step 3: 渲染**

- REWIND 节点：`node.rewindEntryId` 存在时追加“对比两条分支”按钮
  （`data-compare=...`）；
- `onListClick` 分支处理该按钮 → `openCompare(entryId)`；
- 每列按顺序渲染：意图列表、运行统计、关键决定（拒绝审批 / 失败验证 / 未完成待办）、
  文件列表；空侧显示“没有后续动作”；
- 文件差异三组，空组隐藏；
- 请求失败在对话框内显示错误，不静默。

- [ ] **Step 4: 样式与响应式**

桌面 `grid-template-columns: 1fr 1fr`；`@media (max-width: 720px)` 改为单列；
过长路径 `overflow-wrap: anywhere`。

- [ ] **Step 5: 提交**

```bash
git commit -m "feat(m23): add comparison dialog to web timeline"
```

---

### Task 6: CLI 命令

**Files:**
- Create: `pi-java-trigger/.../cli/CliGitSupport.java`
- Create: `pi-java-trigger/.../cli/CliTimelineSupport.java`
- Modify: `pi-java-trigger/.../cli/CliChatSupport.java`
- Modify: `pi-java-trigger/.../cli/CliTerminal.java`
- Modify: `pi-java-trigger/.../cli/CliTui.java`
- Modify: `pi-java-trigger/.../cli/SlashCompleter.java`
- Test: `CliChatSupportTest`、新建 `CliTimelineSupportTest`

- [ ] **Step 1: 拆分依赖以满足 6 参数上限**

`CliGitSupport(CliSessionService, GitCheckpointService, GitUndoService)` 承接
`/diff`、`/undo`；`CliTimelineSupport(SessionTimelineService,
SessionBranchComparisonService, SessionRewindService)` 承接新命令。
`CliChatSupport(sessions, settings, compaction, git, timeline, plans)` 保持 6 个参数。

- [ ] **Step 2: TUI 读取钩子**

```java
private final Function<String, String> tuiReader;
static CliTerminal forTui(PrintWriter out, Function<String, String> reader) { ... }
```

`readLine` 优先使用 `tuiReader`；`CliTui` 传入复用 `LineReader` 的 lambda，
捕获 `UserInterruptException` / `EndOfFileException` 返回 null。

- [ ] **Step 3: 命令实现**

- `/timeline`：RUN/REWIND 顺序编号（从 1 开始），输出序号、时间、意图/简报首句、
  轮数/工具数/拒绝/失败，当前节点标 `*`；
- `/compare [n]`：无参对比最近一次回退；`n` 为 REWIND 序号。输出摘要、两侧统计、
  文件差异；
- `/rewind <n>`：`n` 为 RUN 序号；先 `dryRun=true` 预览；`terminal.readLine`
  确认，仅 `y`/`yes` 执行，回车或 EOF 取消；非交互（TUI reader 为 null）直接拒绝。

- [ ] **Step 4: 帮助、补全、测试**

`COMMANDS` 增加三个命令；`helpText` 同步；`SlashCompleter` 补全项同步。
测试覆盖 `isCommand`、序号解析、取消路径、非交互拒绝。

- [ ] **Step 5: 提交**

```bash
git commit -m "feat(m23): add timeline compare and rewind cli commands"
```

---

### Task 7: 集成测试与实测

**Files:**
- Create: `pi-java-app/src/test/java/com/piagent/app/BranchComparisonIntegrationTest.java`
- Modify: OpenAPI 断言测试

- [ ] **Step 1: 集成测试**

`@SpringBootTest(classes = PiJavaApplication.class, properties = {"pi.fake.demo-mode=true", "pi.git.enabled=true"})`，
临时工作区；跑一轮 → 回退 → 再跑一轮；断言对比两侧与文件分类。

- [ ] **Step 2: OpenAPI 路径断言加入 `/comparison`。**
- [ ] **Step 3: 全量验证**

```bash
mvn -q verify
```

- [ ] **Step 4: 浏览器走查**

启动独立实例（非 8080），新建会话 → 跑一轮 → 回退 → 再对话 → 打开时间线 →
“对比两条分支”，检查桌面与 375px 宽度、控制台无错误。

- [ ] **Step 5: CLI 实测**

行模式执行 `/timeline`、`/compare`、`/rewind`；确认回车取消与 EOF 拒绝。

---

### Task 8: 文档与提交

**Files:**
- Create: `docs/milestones/M23.md`
- Modify: `docs/08-roadmap.md`
- Modify: `docs/05-api-and-cli.md`
- Modify: `docs/11-interface-design.md`

- [ ] **Step 1: 写里程碑记录与文档更新。**
- [ ] **Step 2: 最终全量验证并提交。**

```bash
mvn -q verify
git commit -m "docs(m23): record branch comparison milestone"
```

---

## 验收

- 无回退 `available=false`，Web 不出现对比按钮；
- 回退后继续对话，两侧意图/拒绝/验证/文件分类正确；
- 嵌套回退默认最近一次，指定旧简报 409；
- Web 桌面与 375px 无溢出，控制台无错误；
- CLI 三命令实测通过，非交互拒绝回退；
- `mvn -q verify` 全绿。

## 约束

- 不新增迁移、不改 entry 格式、不访问磁盘；
- 列表与摘要全部服务端截断（20/10/100/500）；
- 前端只使用 `textContent` 与 DOM 构造；
- 不 push、不打 tag，除非用户明确同意。