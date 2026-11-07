# M25 分支切回 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让同一会话内的叶子可以在两条已存在的分支之间来回移动，同时还原对话上下文与工作区，并为每次切换写入一条可再次切回的简报。

**Architecture:** 复用 `BRANCH_SUMMARY` entry，`details.kind` 区分 `rewind` / `resume`。切换只移动 `ChatSession.leafEntryId`，不复制 entry；目标树由 `BranchTrees` 从目标分支的 entry 中提取，执行前用 `git.snapshot` 记录离开侧实时树；工作区通过 `planRestore` 预检、`restore` 写入，冲突时整体放弃。时间线与对比从“只认 rewind”泛化为“认两种分支简报”。

**Tech Stack:** Java 21、Spring Boot 3.5、JUnit 5 + AssertJ + MockMvc、MyBatis、原生 ES Modules（Web）、JLine（CLI）。

---

**Spec:** `docs/superpowers/specs/2026-11-06-branch-resume-design.md`

**Worktree:** `D:\code\pi-java\.worktrees\m25-branch-resume`，分支 `feat/m25-branch-resume`。

**通用验证命令：**

```bash
# 应用层单测
mvn -q -pl pi-java-application -am \
  -Dtest=BranchTreesTest,SessionBranchResumeServiceTest,BranchResumeBriefBuilderTest,SessionRewindServiceTest,RewindBriefBuilderTest,SessionTimelineServiceTest,SessionBranchComparisonServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

# 触发层单测
mvn -q -pl pi-java-trigger -am \
  -Dtest=SessionResumeControllerTest,SessionTimelineControllerTest,SessionComparisonControllerTest,CliTimelineSupportTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

# 全量
mvn -q verify
```

**约定：**

- 所有新公开类型补中文 Javadoc；行宽不超过 100。
- 前端只使用 `textContent` 与 DOM 构造，禁止 `innerHTML`。
- 不新增数据库迁移。
- 每个任务结束本地提交，不推送。

---

## 文件结构

**新建（应用层）**

| 文件 | 职责 |
|---|---|
| `pi-java-application/src/main/java/com/piagent/application/timeline/BranchTrees.java` | 从分支 entry 提取工作区树证据 |
| `pi-java-application/src/main/java/com/piagent/application/timeline/BranchResumeBrief.java` | 切换简报的摘要 + details 值对象 |
| `pi-java-application/src/main/java/com/piagent/application/timeline/BranchResumeBriefBuilder.java` | 确定性生成切换简报 |
| `pi-java-application/src/main/java/com/piagent/application/timeline/BranchResumeResult.java` | 预览/执行结果 |
| `pi-java-application/src/main/java/com/piagent/application/timeline/SessionBranchResumeService.java` | 切换用例 |

**新建（触发层）**

| 文件 | 职责 |
|---|---|
| `pi-java-trigger/src/main/java/com/piagent/trigger/http/session/SessionResumeController.java` | `POST /api/v1/sessions/{id}/resume` |
| `pi-java-trigger/src/main/java/com/piagent/trigger/http/session/SessionResumeRequest.java` | 请求 DTO |
| `pi-java-trigger/src/main/java/com/piagent/trigger/http/session/SessionResumeResponse.java` | 响应 DTO |

**新建（测试）**

| 文件 | 职责 |
|---|---|
| `pi-java-application/src/test/java/com/piagent/application/timeline/BranchTreesTest.java` | 树证据优先级 |
| `pi-java-application/src/test/java/com/piagent/application/timeline/BranchResumeBriefBuilderTest.java` | 简报统计与截断 |
| `pi-java-application/src/test/java/com/piagent/application/timeline/SessionBranchResumeServiceTest.java` | 切换用例 |
| `pi-java-trigger/src/test/java/com/piagent/trigger/http/session/SessionResumeControllerTest.java` | HTTP 映射 |
| `pi-java-app/src/test/java/com/piagent/app/timeline/BranchResumeIntegrationTest.java` | 真实 Git 往返切换 |
| `docs/milestones/M25.md` | 里程碑记录 |

**修改**

- `RewindBriefBuilder.java`：新增 `fromTreeSha` / `toTreeSha`。
- `SessionRewindService.java`：实时快照、保活两侧树、`latestTree` 认识 `toTreeSha`。
- `SessionTimeline.java` / `SessionTimelineService.java`：`RESUME`、`BranchEvidence`、`branch` 字段。
- `SessionTimelineResponse.java`：分支字段改名。
- `SessionBranchComparison.java` / `SessionBranchComparisonService.java` / `BranchComparisonProjector.java`：接受两种简报、字段改名、中性措辞。
- `SessionComparisonController.java` / `SessionComparisonResponse.java`：`branch` 参数与字段。
- `CliTimelineSupport.java` / `CliTimelineText.java` / `CliChatSupport.java` / `SlashCompleter.java`：`/resume` 与分支节点。
- `pi-java-app/src/main/resources/static/js/api.js`：`resumeBranch`、`?branch=`。
- `pi-java-app/src/main/resources/static/js/timeline.js`：分支卡片、切换预览、对比入口。
- `pi-java-app/src/main/resources/static/js/turn-view.js`：切换简报渲染。
- `pi-java-app/src/main/resources/static/index.html`：对比标题、切换按钮、备注文案。
- `pi-java-app/src/main/resources/static/css/timeline.css`：切换按钮与刻度样式。
- 现有测试：`SessionRewindServiceTest`、`RewindBriefBuilderTest`、`SessionTimelineServiceTest`、`SessionBranchComparisonServiceTest`、`BranchComparisonProjectorTest`、`SessionTimelineControllerTest`、`SessionComparisonControllerTest`、`CliTimelineSupportTest`、`BranchComparisonIntegrationTest`、`OpenApiDocumentIntegrationTest`。

---

## Task M25-1：树证据、回退简报树字段与保活

**Files:**
- Create: `pi-java-application/src/main/java/com/piagent/application/timeline/BranchTrees.java`
- Create: `pi-java-application/src/test/java/com/piagent/application/timeline/BranchTreesTest.java`
- Modify: `pi-java-application/src/main/java/com/piagent/application/timeline/RewindBriefBuilder.java`
- Modify: `pi-java-application/src/main/java/com/piagent/application/timeline/SessionRewindService.java`
- Modify: `pi-java-application/src/test/java/com/piagent/application/timeline/RewindBriefBuilderTest.java`
- Modify: `pi-java-application/src/test/java/com/piagent/application/timeline/SessionRewindServiceTest.java`

- [ ] **Step 1: 写 `BranchTreesTest`（先失败）**

```java
package com.piagent.application.timeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.piagent.application.testsupport.SessionEntryFactory;
import com.piagent.domain.git.model.FileChange;
import com.piagent.domain.git.model.GitChangeKind;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.payload.BranchSummaryPayload;
import com.piagent.domain.session.model.payload.EntryPayload;
import com.piagent.domain.session.model.payload.GitChangeSetPayload;
import com.piagent.domain.session.model.payload.GitCheckpointPayload;
import com.piagent.domain.session.model.payload.MessagePayload;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BranchTreesTest {

    private static final SessionId SESSION = SessionEntryFactory.sessionId(1);

    @Test
    void usesTheLatestEntryThatCarriesATree() {
        List<SessionEntry> branch = new ArrayList<>();
        branch.add(entry(1, null, new GitCheckpointPayload("run-1", null, "tree-0",
                "head-0", "main")));
        branch.add(entry(2, branch.getLast().id(), change("tree-1")));
        branch.add(entry(3, branch.getLast().id(), new GitCheckpointPayload("run-2",
                branch.getLast().id(), "tree-2", "head-1", "main")));

        assertThat(BranchTrees.treeOf(branch)).isEqualTo("tree-2");
    }

    @Test
    void prefersPositionOverPayloadType() {
        List<SessionEntry> branch = new ArrayList<>();
        branch.add(entry(1, null, change("tree-old")));
        branch.add(entry(2, branch.getLast().id(), new GitCheckpointPayload("run-1",
                null, "tree-new", "head", "main")));

        assertThat(BranchTrees.treeOf(branch)).isEqualTo("tree-new");
    }

    @Test
    void fallsBackToBranchSummaryToTreeSha() {
        List<SessionEntry> branch = new ArrayList<>();
        branch.add(entry(1, null, new MessagePayload(SessionEntryFactory.user("开始"))));
        branch.add(entry(2, branch.getLast().id(), new BranchSummaryPayload(
                SessionEntryFactory.entryId(9), "切回另一条分支", Map.of(
                        "kind", "resume", "toTreeSha", "tree-brief"))));

        assertThat(BranchTrees.treeOf(branch)).isEqualTo("tree-brief");
    }

    @Test
    void ignoresBlankEvidenceAndReturnsNullWhenNothingExists() {
        List<SessionEntry> branch = new ArrayList<>();
        branch.add(entry(1, null, new GitCheckpointPayload("run-1", null, " ",
                "head", "main")));
        branch.add(entry(2, branch.getLast().id(), new MessagePayload(
                SessionEntryFactory.user("无树"))));

        assertThat(BranchTrees.treeOf(branch)).isNull();
    }

    private SessionEntry entry(long seq, com.piagent.domain.session.model.EntryId parent,
            EntryPayload payload) {
        return SessionEntryFactory.entry(SESSION, seq, parent, payload, 0);
    }

    private GitChangeSetPayload change(String treeSha) {
        return new GitChangeSetPayload("run-1", 1, null, "tree-0", treeSha,
                List.of(new FileChange("a.txt", GitChangeKind.MODIFIED, 1, 0, false)),
                "diff", false, "");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -pl pi-java-application -am -Dtest=BranchTreesTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: 编译失败，`BranchTrees` 不存在。

- [ ] **Step 3: 实现 `BranchTrees`**

```java
package com.piagent.application.timeline;

import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.payload.BranchSummaryPayload;
import com.piagent.domain.session.model.payload.GitChangeSetPayload;
import com.piagent.domain.session.model.payload.GitCheckpointPayload;
import java.util.List;

/**
 * 从分支 entry 中提取工作区树证据。
 *
 * <p>分支状态以位置更晚的 entry 为准，因此从叶子向前扫描，第一个非空树即答案。
 * 同一位置只可能有一种 payload，不存在跨类型的全局优先级。</p>
 */
final class BranchTrees {

    private BranchTrees() {
    }

    /**
     * 返回分支上最后一个可用的工作区树。
     *
     * @param branch 从根到叶子的 entry
     * @return 树对象；没有任何证据时为 null
     */
    static String treeOf(List<SessionEntry> branch) {
        for (int index = branch.size() - 1; index >= 0; index--) {
            String tree = treeOf(branch.get(index));
            if (tree != null && !tree.isBlank()) {
                return tree;
            }
        }
        return null;
    }

    private static String treeOf(SessionEntry entry) {
        Object payload = entry.payload();
        if (payload instanceof GitChangeSetPayload change) {
            return change.treeSha();
        }
        if (payload instanceof GitCheckpointPayload checkpoint) {
            return checkpoint.baseTreeSha();
        }
        if (payload instanceof BranchSummaryPayload summary) {
            Object tree = summary.details().get("toTreeSha");
            return tree == null ? null : String.valueOf(tree);
        }
        return null;
    }
}
```

- [ ] **Step 4: 扩展 `RewindBriefBuilder`**

把方法签名改为：

```java
public RewindBrief build(SessionEntry checkpointEntry, List<SessionEntry> discarded,
        List<String> restoredFiles, String fromTreeSha, String toTreeSha, String note) {
```

在 `details(...)` 中加入：

```java
putIfPresent(details, "fromTreeSha", fromTreeSha);
putIfPresent(details, "toTreeSha", toTreeSha);
```

并新增：

```java
private void putIfPresent(Map<String, Object> details, String key, String value) {
    if (value != null && !value.isBlank()) {
        details.put(key, value);
    }
}
```

把 `details(...)` 的调用改为把 `fromTreeSha` / `toTreeSha` 传进去：

```java
return new RewindBrief(summary, details(checkpointEntry, checkpoint, discarded, facts,
        fromTreeSha, toTreeSha));
```

`BranchSummaryPayload` 会对 details 做 `Map.copyOf`，因此绝不能放 null 值；空串也不放。

- [ ] **Step 5: 更新 `RewindBriefBuilderTest`**

所有 `builder.build(...)` 调用在 `restoredFiles` 后补 `"tree-from", "tree-to"`。新增断言：

```java
assertThat(brief.details())
        .containsEntry("fromTreeSha", "tree-from")
        .containsEntry("toTreeSha", "tree-to");
```

- [ ] **Step 6: 修改 `SessionRewindService`**

预览阶段把记录树传给简报：

```java
String currentTree = latestTree(branch, index, checkpoint.baseTreeSha());
GitRestorePlan plan = plan(session, checkpoint.baseTreeSha(), currentTree);
RewindBrief preview = briefs.build(checkpointEntry, discarded, plan.files(),
        currentTree, checkpoint.baseTreeSha(), note);
```

执行阶段在移动叶子前捕获实时快照并重建简报：

```java
String fromTree = snapshotTree(session, currentTree);
RewindBrief live = briefs.build(checkpointEntry, discarded, plan.files(),
        fromTree, checkpoint.baseTreeSha(), note);
EntryId oldLeaf = session.leafEntryId();
sessions.update(session.rewind(checkpoint.baseLeafId()));
SessionEntry briefEntry = summaries.append(sessionId,
        new BranchSummaryPayload(oldLeaf, live.summary(), live.details()));
GitRestoreResult restored = restore(session, checkpoint.baseTreeSha(), currentTree);
if (!restored.complete()) {
    throw new DomainException(DomainErrorCode.GIT_OPERATION_FAILED,
            "确认后工作区又发生了变化，已放弃本次回退，请重试");
}
pin(session, oldLeaf, fromTree);
pin(session, checkpoint.baseLeafId(), checkpoint.baseTreeSha());
return new SessionRewindResult(false, true, true, plan.files(), restored.restored(),
        List.of(), briefEntry.id(), discarded.size(), countRuns(discarded),
        live.summary());
```

新增辅助方法：

```java
private String snapshotTree(ChatSession session, String fallback) {
    try {
        return git.snapshot(session.config().cwd())
                .map(GitSnapshot::treeSha)
                .filter(tree -> !tree.isBlank())
                .orElse(fallback);
    } catch (RuntimeException error) {
        return fallback;
    }
}

private void pin(ChatSession session, EntryId leaf, String treeSha) {
    if (leaf == null || treeSha == null || treeSha.isBlank()) {
        return;
    }
    try {
        git.pinCheckpoint(session.config().cwd(), session.identity().id().toString(),
                "branch-" + leaf, treeSha);
    } catch (RuntimeException error) {
        // 保活是尽力而为，失败不影响已经完成的切换。
    }
}
```

`latestTree` 增加分支简报：

```java
} else if (payload instanceof BranchSummaryPayload summary) {
    Object tree = summary.details().get("toTreeSha");
    if (tree != null && !String.valueOf(tree).isBlank()) {
        latest = String.valueOf(tree);
    }
}
```

导入 `GitSnapshot`、`BranchSummaryPayload`（已导入）。

- [ ] **Step 7: 扩展 `SessionRewindServiceTest`**

`FakeGit` 增加：

```java
private final List<Pin> pins = new ArrayList<>();

@Override
public void pinCheckpoint(Path workspace, String namespace, String checkpointId,
        String treeSha) {
    pins.add(new Pin(namespace, checkpointId, treeSha));
}

private record Pin(String namespace, String checkpointId, String treeSha) {
}
```

新增测试：

```java
@Test
void executionRecordsLiveSnapshotAndPinsLeftAndTargetTrees() {
    RunIds runs = threeRuns();
    EntryId oldLeaf = sessions.findById(sessionId).orElseThrow().leafEntryId();
    git.nextPlan = new GitRestorePlan("tree-0", "tree-2", List.of("a.txt"), List.of());
    git.nextRestore = new GitRestoreResult(List.of("a.txt"), List.of());
    git.snapshots.add(new GitSnapshot("tree-live", "head", "main"));

    SessionRewindResult result = service.rewind(sessionId, runs.secondCheckpoint, false, null);

    SessionEntry brief = entries.findById(sessionId, result.briefEntryId()).orElseThrow();
    BranchSummaryPayload payload = (BranchSummaryPayload) brief.payload();
    assertThat(payload.details().get("fromTreeSha")).isEqualTo("tree-live");
    assertThat(payload.details().get("toTreeSha")).isEqualTo("tree-1");
    assertThat(git.pins).contains(
            new FakeGit.Pin(sessionId.toString(), "branch-" + oldLeaf, "tree-live"),
            new FakeGit.Pin(sessionId.toString(), "branch-" + runs.secondBaseLeaf, "tree-1"));
}
```

导入 `BranchSummaryPayload`。

- [ ] **Step 8: 跑测试**

Run 通用验证命令中的应用层单测。
Expected: 全部通过。

- [ ] **Step 9: 提交**

```bash
git add pi-java-application/src/main/java/com/piagent/application/timeline/BranchTrees.java \
  pi-java-application/src/main/java/com/piagent/application/timeline/RewindBriefBuilder.java \
  pi-java-application/src/main/java/com/piagent/application/timeline/SessionRewindService.java \
  pi-java-application/src/test/java/com/piagent/application/timeline/
git commit -m "feat(branch): record tree evidence for rewind and resume"
```

---

## Task M25-2：切换用例、结果与简报

**Files:**
- Create: `pi-java-application/src/main/java/com/piagent/application/timeline/BranchResumeResult.java`
- Create: `pi-java-application/src/main/java/com/piagent/application/timeline/BranchResumeBrief.java`
- Create: `pi-java-application/src/main/java/com/piagent/application/timeline/BranchResumeBriefBuilder.java`
- Create: `pi-java-application/src/main/java/com/piagent/application/timeline/SessionBranchResumeService.java`
- Create: `pi-java-application/src/test/java/com/piagent/application/timeline/BranchResumeBriefBuilderTest.java`
- Create: `pi-java-application/src/test/java/com/piagent/application/timeline/SessionBranchResumeServiceTest.java`

- [ ] **Step 1: 写 `BranchResumeResult`**

```java
package com.piagent.application.timeline;

import com.piagent.domain.git.model.GitConflict;
import com.piagent.domain.session.model.EntryId;
import java.util.List;

/**
 * 一次分支切换请求的结果，预览与执行共用。
 *
 * @param dryRun 是否只是预览
 * @param switched 是否已完成切换
 * @param available 目标树与当前树是否都可用
 * @param restorable 工作区是否可以安全还原
 * @param unavailableReason 不可用原因；可用时为空串
 * @param targetLeafId 目标分支叶子
 * @param files 计划改动的工作区路径
 * @param restored 实际写入或删除的路径
 * @param conflicts 冲突列表
 * @param briefEntryId 新写入的切换简报；预览或失败时为 null
 * @param leftEntries 离开分支尾段的 entry 数
 * @param leftRuns 离开分支尾段的 run 数
 * @param targetEntries 目标分支尾段的 entry 数
 * @param targetRuns 目标分支尾段的 run 数
 * @param brief 简报正文；预览也返回
 */
public record BranchResumeResult(boolean dryRun, boolean switched, boolean available,
        boolean restorable, String unavailableReason, EntryId targetLeafId, List<String> files,
        List<String> restored, List<GitConflict> conflicts, EntryId briefEntryId,
        int leftEntries, int leftRuns, int targetEntries, int targetRuns, String brief) {

    /**
     * 规范化列表与文本字段。
     *
     * @param dryRun 是否只是预览
     * @param switched 是否已完成切换
     * @param available 树是否可用
     * @param restorable 是否可以安全还原
     * @param unavailableReason 不可用原因
     * @param targetLeafId 目标叶子
     * @param files 计划路径
     * @param restored 实际路径
     * @param conflicts 冲突
     * @param briefEntryId 简报 entry
     * @param leftEntries 离开侧 entry 数
     * @param leftRuns 离开侧 run 数
     * @param targetEntries 目标侧 entry 数
     * @param targetRuns 目标侧 run 数
     * @param brief 简报正文
     */
    public BranchResumeResult {
        unavailableReason = unavailableReason == null ? "" : unavailableReason;
        files = files == null ? List.of() : List.copyOf(files);
        restored = restored == null ? List.of() : List.copyOf(restored);
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        brief = brief == null ? "" : brief;
    }
}
```

- [ ] **Step 2: 写 `BranchResumeBrief` 与 `BranchResumeBriefBuilder`**

`BranchResumeBrief.java`：

```java
package com.piagent.application.timeline;

import java.util.Map;

/**
 * 切换后写入会话的结构化简报。
 *
 * @param summary 不超过 400 字的摘要
 * @param details 结构化细节，写入 {@code BranchSummaryPayload.details}
 */
public record BranchResumeBrief(String summary, Map<String, Object> details) {
}
```

`BranchResumeBriefBuilder.java`：

```java
package com.piagent.application.timeline;

import com.piagent.domain.governance.model.ApprovalStatus;
import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.message.TextBlock;
import com.piagent.domain.session.model.message.UserMessage;
import com.piagent.domain.session.model.payload.ApprovalPayload;
import com.piagent.domain.session.model.payload.GitCheckpointPayload;
import com.piagent.domain.session.model.payload.MessagePayload;
import com.piagent.domain.session.model.payload.VerificationPayload;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 只用结构数据生成分支切换简报，不调用 LLM，也不包含文件内容或完整验证输出。
 */
@Component
public class BranchResumeBriefBuilder {

    private static final int MAX_SUMMARY = 400;
    private static final int MAX_NOTE = 200;
    private static final int MAX_INTENT = 80;
    private static final int MAX_DETAIL_PATHS = 50;

    /**
     * 校验备注长度。
     *
     * @param note 用户备注，可为 null
     */
    public void validateNote(String note) {
        requireNote(note);
    }

    /**
     * 生成切换简报。
     *
     * @param branchEntryId 发起切换的简报 entry
     * @param left 离开分支的尾段
     * @param target 目标分支的尾段
     * @param changedFiles 计划改动的工作区路径
     * @param fromTreeSha 离开侧实时树
     * @param toTreeSha 目标树
     * @param note 用户备注，可为 null
     * @return 摘要与 details
     */
    public BranchResumeBrief build(EntryId branchEntryId, List<SessionEntry> left,
            List<SessionEntry> target, List<String> changedFiles, String fromTreeSha,
            String toTreeSha, String note) {
        String cleanNote = requireNote(note);
        Scan leftScan = scan(left);
        Scan targetScan = scan(target);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("kind", "resume");
        details.put("branchEntryId", branchEntryId.toString());
        putIfPresent(details, "fromTreeSha", fromTreeSha);
        putIfPresent(details, "toTreeSha", toTreeSha);
        details.put("leftEntries", left.size());
        details.put("leftRuns", leftScan.runs());
        details.put("targetEntries", target.size());
        details.put("targetRuns", targetScan.runs());
        details.put("restoredFiles", changedFiles.size());
        details.put("restoredPaths", changedFiles.stream().limit(MAX_DETAIL_PATHS).toList());
        details.put("deniedApprovals", targetScan.denied());
        details.put("failedVerifications", targetScan.failed());
        return new BranchResumeBrief(render(leftScan, targetScan, changedFiles, cleanNote),
                Collections.unmodifiableMap(details));
    }

    private String requireNote(String note) {
        String cleanNote = note == null ? "" : note.strip();
        if (cleanNote.length() > MAX_NOTE) {
            throw new DomainException(DomainErrorCode.INVALID_ARGUMENT,
                    "备注不能超过 " + MAX_NOTE + " 字");
        }
        return cleanNote;
    }

    private Scan scan(List<SessionEntry> segment) {
        int userMessages = 0;
        int runs = 0;
        int denied = 0;
        int failed = 0;
        String lastIntent = "";
        Set<String> deniedTools = new LinkedHashSet<>();
        Set<String> failedCommands = new LinkedHashSet<>();
        for (SessionEntry entry : segment) {
            Object payload = entry.payload();
            if (payload instanceof MessagePayload message
                    && message.message() instanceof UserMessage user) {
                userMessages++;
                String text = userText(user);
                if (!text.isBlank()) {
                    lastIntent = text;
                }
            } else if (payload instanceof GitCheckpointPayload) {
                runs++;
            } else if (payload instanceof ApprovalPayload approval
                    && ApprovalStatus.DENIED.name().equalsIgnoreCase(approval.decision())) {
                denied++;
                deniedTools.add(approval.toolName());
            } else if (payload instanceof VerificationPayload verification
                    && !verification.passed()) {
                failed++;
                failedCommands.add(verification.command());
            }
        }
        return new Scan(userMessages, runs, denied, failed, truncate(lastIntent, MAX_INTENT),
                List.copyOf(deniedTools), List.copyOf(failedCommands));
    }

    private String render(Scan left, Scan target, List<String> changedFiles, String note) {
        StringBuilder text = new StringBuilder("切回另一条分支：离开 ")
                .append(left.userMessages()).append(" 轮对话、").append(left.runs())
                .append(" 个 run；目标分支有 ").append(target.userMessages())
                .append(" 轮对话、").append(target.runs()).append(" 个 run。");
        text.append("已改动 ").append(changedFiles.size()).append(" 个文件");
        if (!changedFiles.isEmpty()) {
            text.append("（").append(joinLimit(changedFiles, 3)).append("）");
        }
        text.append("。");
        if (!target.lastIntent().isBlank()) {
            text.append("目标分支最后一次意图：").append(target.lastIntent()).append("。");
        }
        List<String> avoid = new ArrayList<>();
        target.deniedTools().stream().limit(3)
                .forEach(tool -> avoid.add(tool + "（已拒绝）"));
        target.failedCommands().stream().limit(3)
                .forEach(command -> avoid.add(command + "（已验证失败）"));
        if (!avoid.isEmpty()) {
            text.append("继续时不要重复：").append(String.join("、", avoid)).append("。");
        }
        if (!note.isBlank()) {
            text.append("备注：").append(note).append("。");
        }
        String summary = text.toString();
        return summary.length() <= MAX_SUMMARY ? summary
                : summary.substring(0, MAX_SUMMARY - 1) + "…";
    }

    private String userText(UserMessage user) {
        StringBuilder text = new StringBuilder();
        for (var block : user.content()) {
            if (block instanceof TextBlock content) {
                text.append(content.text());
            }
        }
        return text.toString().strip();
    }

    private void putIfPresent(Map<String, Object> details, String key, String value) {
        if (value != null && !value.isBlank()) {
            details.put(key, value);
        }
    }

    private String joinLimit(List<String> values, int limit) {
        String joined = String.join("、", values.stream().limit(limit).toList());
        return values.size() > limit ? joined + " 等" : joined;
    }

    private String truncate(String text, int limit) {
        return text.length() <= limit ? text : text.substring(0, limit - 1) + "…";
    }

    private record Scan(int userMessages, int runs, int denied, int failed, String lastIntent,
            List<String> deniedTools, List<String> failedCommands) {
    }
}
```

- [ ] **Step 3: 写 `BranchResumeBriefBuilderTest`**

```java
package com.piagent.application.timeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.application.testsupport.SessionEntryFactory;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.payload.ApprovalPayload;
import com.piagent.domain.session.model.payload.EntryPayload;
import com.piagent.domain.session.model.payload.GitCheckpointPayload;
import com.piagent.domain.session.model.payload.MessagePayload;
import com.piagent.domain.session.model.payload.VerificationPayload;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BranchResumeBriefBuilderTest {

    private static final SessionId SESSION = SessionEntryFactory.sessionId(2);
    private final BranchResumeBriefBuilder builder = new BranchResumeBriefBuilder();

    @Test
    void rendersBothSidesIntentAndAvoidList() {
        List<SessionEntry> left = List.of(
                entry(1, new MessagePayload(SessionEntryFactory.user("旧思路"))),
                entry(2, new GitCheckpointPayload("run-l", null, "tree-l", "head", "main")));
        List<SessionEntry> target = List.of(
                entry(3, new MessagePayload(SessionEntryFactory.user("第一版"))),
                entry(4, new MessagePayload(SessionEntryFactory.user("修复登录超时"))),
                entry(5, new GitCheckpointPayload("run-t", null, "tree-t", "head", "main")),
                entry(6, new ApprovalPayload("a1", "c1", "write", "DENIED", "先别改", "user")),
                entry(7, new VerificationPayload("run-t", "mvn test", false, 1, false, false,
                        90, "fail")));

        BranchResumeBrief brief = builder.build(SessionEntryFactory.entryId(50), left, target,
                List.of("a.java", "b.java"), "tree-live", "tree-t", "先跑测试");

        assertThat(brief.summary())
                .contains("离开 1 轮对话、1 个 run")
                .contains("目标分支有 2 轮对话、1 个 run")
                .contains("已改动 2 个文件（a.java、b.java）")
                .contains("目标分支最后一次意图：修复登录超时")
                .contains("write（已拒绝）")
                .contains("mvn test（已验证失败）")
                .contains("备注：先跑测试");
        assertThat(brief.details())
                .containsEntry("kind", "resume")
                .containsEntry("branchEntryId", SessionEntryFactory.entryId(50).toString())
                .containsEntry("leftEntries", 2)
                .containsEntry("leftRuns", 1)
                .containsEntry("targetEntries", 5)
                .containsEntry("targetRuns", 1)
                .containsEntry("restoredFiles", 2)
                .containsEntry("fromTreeSha", "tree-live")
                .containsEntry("toTreeSha", "tree-t");
        assertThat((List<?>) brief.details().get("restoredPaths"))
                .containsExactly("a.java", "b.java");
    }

    @Test
    void truncatesSummaryAtFourHundredCharacters() {
        List<SessionEntry> target = new ArrayList<>();
        for (int index = 0; index < 30; index++) {
            target.add(entry(100 + index, new MessagePayload(
                    SessionEntryFactory.user("很长的目标意图".repeat(5)))));
        }

        BranchResumeBrief brief = builder.build(SessionEntryFactory.entryId(51), List.of(),
                target, List.of(), "from", "to", "");

        assertThat(brief.summary()).hasSize(400).endsWith("…");
    }

    @Test
    void rejectsTooLongNote() {
        assertThatThrownBy(() -> builder.build(SessionEntryFactory.entryId(52), List.of(),
                List.of(), List.of(), "from", "to", "x".repeat(201)))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.INVALID_ARGUMENT));
    }

    private SessionEntry entry(long seq, EntryPayload payload) {
        return SessionEntryFactory.entry(SESSION, seq, null, payload, 0);
    }
}
```

- [ ] **Step 4: 跑测试确认失败**

Run: `mvn -q -pl pi-java-application -am -Dtest=BranchResumeBriefBuilderTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: 编译失败，类不存在。

- [ ] **Step 5: 实现 `SessionBranchResumeService`**

```java
package com.piagent.application.timeline;

import com.piagent.domain.git.model.GitRestorePlan;
import com.piagent.domain.git.model.GitRestoreResult;
import com.piagent.domain.git.model.GitSnapshot;
import com.piagent.domain.git.port.WorkspaceGit;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.SessionStatus;
import com.piagent.domain.session.model.payload.BranchSummaryPayload;
import com.piagent.domain.session.port.SessionEntryStore;
import com.piagent.domain.session.port.SessionRepository;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 切到同一会话的另一条已知分支。
 *
 * <p>切换只移动叶子，不复制 entry；新简报的 {@code fromEntryId} 指向刚离开的叶子，
 * 因此沿同一条简报可以原路切回。目标树优先取发起简报的 {@code fromTreeSha}，
 * 旧数据没有时沿目标分支回退查找。任何冲突都整体放弃，不写叶子、不写工作区。</p>
 */
@Service
public class SessionBranchResumeService {

    private static final int MAX_ERROR_LENGTH = 400;
    private static final System.Logger LOG =
            System.getLogger(SessionBranchResumeService.class.getName());

    private final SessionRepository sessions;
    private final SessionEntryStore entries;
    private final WorkspaceGit git;
    private final BranchSummaryAppender summaries;
    private final BranchResumeBriefBuilder briefs;

    /**
     * 创建切换服务。
     *
     * @param sessions 会话仓储
     * @param entries entry 存储
     * @param git 工作区 Git 端口
     * @param summaries 分支简报追加器
     * @param briefs 切换简报生成器
     */
    public SessionBranchResumeService(SessionRepository sessions, SessionEntryStore entries,
            WorkspaceGit git, BranchSummaryAppender summaries, BranchResumeBriefBuilder briefs) {
        this.sessions = sessions;
        this.entries = entries;
        this.git = git;
        this.summaries = summaries;
        this.briefs = briefs;
    }

    /**
     * 预览或执行一次分支切换。
     *
     * @param sessionId 会话标识
     * @param branchEntryId 当前分支上的分支简报
     * @param dryRun true 表示只预览
     * @param note 用户备注，可为 null
     * @return 预览或执行结果
     */
    @Transactional
    public BranchResumeResult resume(SessionId sessionId, EntryId branchEntryId,
            boolean dryRun, String note) {
        Objects.requireNonNull(branchEntryId, "branchEntryId 不能为空");
        ChatSession session = require(sessionId);
        if (session.status() == SessionStatus.ARCHIVED) {
            throw new DomainException(DomainErrorCode.SESSION_ARCHIVED, "已归档会话不能切换分支");
        }
        if (session.status() != SessionStatus.IDLE) {
            throw new DomainException(DomainErrorCode.INVALID_STATE_TRANSITION,
                    "只有空闲会话可以切换分支，当前状态: " + session.status());
        }
        briefs.validateNote(note);
        List<SessionEntry> current = entries.loadBranch(sessionId, session.leafEntryId());
        int briefIndex = indexOf(current, branchEntryId);
        if (briefIndex < 0) {
            throw new DomainException(DomainErrorCode.INVALID_BRANCH,
                    "切换点不在当前分支上: " + branchEntryId);
        }
        SessionEntry briefEntry = current.get(briefIndex);
        if (!(briefEntry.payload() instanceof BranchSummaryPayload payload)
                || !isBranch(payload)) {
            throw new DomainException(DomainErrorCode.INVALID_BRANCH,
                    "目标不是分支简报: " + branchEntryId);
        }
        EntryId targetLeaf = payload.fromEntryId();
        List<SessionEntry> target = loadTarget(sessionId, targetLeaf);
        if (target.isEmpty() || indexOf(current, targetLeaf) >= 0) {
            throw new DomainException(DomainErrorCode.INVALID_BRANCH,
                    "目标叶子不在另一条分支上: " + targetLeaf);
        }
        int fork = commonPrefix(current, target);
        List<SessionEntry> left = List.copyOf(current.subList(fork, current.size()));
        List<SessionEntry> targetSegment = List.copyOf(target.subList(fork, target.size()));
        int leftRuns = countRuns(left);
        int targetRuns = countRuns(targetSegment);

        String targetTree = text(payload.details().get("fromTreeSha"));
        if (targetTree == null) {
            targetTree = BranchTrees.treeOf(target);
        }
        String currentTree = BranchTrees.treeOf(current);
        if (targetTree == null || currentTree == null) {
            String reason = targetTree == null
                    ? "目标分支没有可用的工作区快照，无法安全切换；请先在目标分支建立一次检查点"
                    : "当前分支没有可用的工作区快照，无法安全切换；请先完成一轮对话";
            return new BranchResumeResult(dryRun, false, false, false, reason, targetLeaf,
                    List.of(), List.of(), List.of(), null, left.size(), leftRuns,
                    targetSegment.size(), targetRuns, "");
        }
        GitRestorePlan plan;
        try {
            plan = git.planRestore(session.config().cwd(), targetTree, currentTree);
        } catch (RuntimeException error) {
            return new BranchResumeResult(dryRun, false, false, false,
                    "读取还原计划失败：" + message(error), targetLeaf, List.of(), List.of(),
                    List.of(), null, left.size(), leftRuns, targetSegment.size(), targetRuns, "");
        }
        BranchResumeBrief preview = briefs.build(branchEntryId, left, targetSegment,
                plan.files(), currentTree, targetTree, note);
        if (dryRun) {
            return new BranchResumeResult(true, false, true, plan.restorable(), "", targetLeaf,
                    plan.files(), List.of(), plan.conflicts(), null, left.size(), leftRuns,
                    targetSegment.size(), targetRuns, preview.summary());
        }
        if (!plan.restorable()) {
            return new BranchResumeResult(false, false, true, false, "", targetLeaf,
                    plan.files(), List.of(), plan.conflicts(), null, left.size(), leftRuns,
                    targetSegment.size(), targetRuns, preview.summary());
        }

        String fromTree = snapshotTree(session, currentTree);
        BranchResumeBrief live = briefs.build(branchEntryId, left, targetSegment,
                plan.files(), fromTree, targetTree, note);
        EntryId oldLeaf = session.leafEntryId();
        sessions.update(session.moveLeaf(targetLeaf));
        SessionEntry brief = summaries.append(sessionId,
                new BranchSummaryPayload(oldLeaf, live.summary(), live.details()));
        GitRestoreResult restored = restore(session, targetTree, currentTree);
        if (!restored.complete()) {
            throw new DomainException(DomainErrorCode.GIT_OPERATION_FAILED,
                    "确认后工作区又发生了变化，已放弃本次切换，请重试");
        }
        pin(session, oldLeaf, fromTree);
        pin(session, targetLeaf, targetTree);
        return new BranchResumeResult(false, true, true, true, "", targetLeaf, plan.files(),
                restored.restored(), List.of(), brief.id(), left.size(), leftRuns,
                targetSegment.size(), targetRuns, live.summary());
    }

    private List<SessionEntry> loadTarget(SessionId sessionId, EntryId targetLeaf) {
        if (targetLeaf == null) {
            return List.of();
        }
        try {
            return entries.loadBranch(sessionId, targetLeaf);
        } catch (DomainException error) {
            if (error.code() == DomainErrorCode.ENTRY_NOT_FOUND) {
                throw new DomainException(DomainErrorCode.INVALID_BRANCH,
                        "目标叶子不存在或不属于本会话: " + targetLeaf);
            }
            throw error;
        }
    }

    private String snapshotTree(ChatSession session, String fallback) {
        try {
            return git.snapshot(session.config().cwd())
                    .map(GitSnapshot::treeSha)
                    .filter(tree -> !tree.isBlank())
                    .orElse(fallback);
        } catch (RuntimeException error) {
            return fallback;
        }
    }

    private GitRestoreResult restore(ChatSession session, String targetTree, String latestTree) {
        try {
            return git.restore(session.config().cwd(), targetTree, latestTree);
        } catch (DomainException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new DomainException(DomainErrorCode.GIT_OPERATION_FAILED,
                    "还原工作区失败: " + message(error));
        }
    }

    private void pin(ChatSession session, EntryId leaf, String treeSha) {
        if (leaf == null || treeSha == null || treeSha.isBlank()) {
            return;
        }
        try {
            git.pinCheckpoint(session.config().cwd(), session.identity().id().toString(),
                    "branch-" + leaf, treeSha);
        } catch (RuntimeException error) {
            LOG.log(System.Logger.Level.WARNING,
                    "保活分支树失败: " + session.identity().id(), error);
        }
    }

    private boolean isBranch(BranchSummaryPayload payload) {
        Object kind = payload.details().get("kind");
        return "rewind".equals(kind) || "resume".equals(kind);
    }

    private int indexOf(List<SessionEntry> branch, EntryId entryId) {
        for (int index = 0; index < branch.size(); index++) {
            if (branch.get(index).id().equals(entryId)) {
                return index;
            }
        }
        return -1;
    }

    private int commonPrefix(List<SessionEntry> left, List<SessionEntry> right) {
        int shared = Math.min(left.size(), right.size());
        int index = 0;
        while (index < shared && left.get(index).id().equals(right.get(index).id())) {
            index++;
        }
        return index;
    }

    private int countRuns(List<SessionEntry> segment) {
        return (int) segment.stream()
                .filter(entry -> entry.payload() instanceof com.piagent.domain.session.model
                        .payload.GitCheckpointPayload)
                .count();
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private ChatSession require(SessionId sessionId) {
        return sessions.findById(sessionId)
                .orElseThrow(() -> new DomainException(DomainErrorCode.SESSION_NOT_FOUND,
                        "会话不存在: " + sessionId));
    }

    private String message(Throwable error) {
        String text = error.getMessage() == null
                ? error.getClass().getSimpleName() : error.getMessage();
        return text.length() <= MAX_ERROR_LENGTH ? text : text.substring(0, MAX_ERROR_LENGTH);
    }
}
```

把 `countRuns` 里的全限定名改成顶部导入 `GitCheckpointPayload`。

- [ ] **Step 6: 写 `SessionBranchResumeServiceTest`**

手工构造两条分支，避免依赖完整 Agent Loop：

```java
package com.piagent.application.timeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.application.testsupport.InMemorySessionEntryStore;
import com.piagent.application.testsupport.InMemorySessionRepository;
import com.piagent.application.testsupport.SequentialIdGenerator;
import com.piagent.application.testsupport.SessionEntryFactory;
import com.piagent.domain.git.model.GitChangeSet;
import com.piagent.domain.git.model.GitRestorePlan;
import com.piagent.domain.git.model.GitRestoreResult;
import com.piagent.domain.git.model.GitSnapshot;
import com.piagent.domain.git.port.WorkspaceGit;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.SandboxSnapshot;
import com.piagent.domain.session.model.SessionConfig;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.ThinkingLevel;
import com.piagent.domain.session.model.payload.BranchSummaryPayload;
import com.piagent.domain.session.model.payload.EntryPayload;
import com.piagent.domain.session.model.payload.GitChangeSetPayload;
import com.piagent.domain.session.model.payload.GitCheckpointPayload;
import com.piagent.domain.session.model.payload.MessagePayload;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SessionBranchResumeServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");
    private static final Path WORKSPACE = Path.of("target/resume-unit").toAbsolutePath();

    private final InMemorySessionRepository sessions = new InMemorySessionRepository();
    private final InMemorySessionEntryStore entries = new InMemorySessionEntryStore();
    private final SequentialIdGenerator ids = new SequentialIdGenerator();
    private final BranchSummaryAppender summaries = new BranchSummaryAppender(sessions, entries,
            ids, Clock.fixed(NOW, ZoneOffset.UTC));
    private final FakeGit git = new FakeGit();
    private final SessionBranchResumeService service = new SessionBranchResumeService(sessions,
            entries, git, summaries, new BranchResumeBriefBuilder());

    private SessionId sessionId;
    private EntryId leaf;
    private long seq;

    @BeforeEach
    void setUp() {
        sessionId = SessionId.newId(ids);
        sessions.insert(ChatSession.createRoot(sessionId, "切换测试",
                new SessionConfig(WORKSPACE, "fake-demo", ThinkingLevel.OFF,
                        SandboxSnapshot.forWorkspace(WORKSPACE))));
    }

    @Test
    void previewsTargetTreeAndCurrentTree() {
        Fixture fixture = twoBranches();

        BranchResumeResult result = service.resume(sessionId, fixture.rewindBrief(), true, null);

        assertThat(result.dryRun()).isTrue();
        assertThat(result.switched()).isFalse();
        assertThat(result.available()).isTrue();
        assertThat(result.restorable()).isTrue();
        assertThat(result.files()).containsExactly("b.txt");
        assertThat(result.leftEntries()).isEqualTo(5);
        assertThat(result.leftRuns()).isEqualTo(1);
        assertThat(result.targetEntries()).isEqualTo(4);
        assertThat(result.targetRuns()).isEqualTo(2);
        assertThat(git.lastPlanTarget).isEqualTo("tree-a2");
        assertThat(git.lastPlanLatest).isEqualTo("tree-b1");
        assertThat(result.brief()).contains("切回另一条分支");
        assertThat(entries.countBySession(sessionId)).isEqualTo(fixture.entryCount());
    }

    @Test
    void switchesLeafAppendsBriefAndRestoresWorkspace() {
        Fixture fixture = twoBranches();
        git.snapshots.add(new GitSnapshot("tree-live-b", "head", "main"));
        git.nextRestore = new GitRestoreResult(List.of("b.txt"), List.of());

        BranchResumeResult result = service.resume(sessionId, fixture.rewindBrief(), false,
                "先跑测试");

        assertThat(result.switched()).isTrue();
        assertThat(result.targetLeafId()).isEqualTo(fixture.targetLeaf());
        assertThat(result.restored()).containsExactly("b.txt");
        SessionEntry brief = entries.findById(sessionId, result.briefEntryId()).orElseThrow();
        assertThat(brief.parentId()).isEqualTo(fixture.targetLeaf());
        BranchSummaryPayload payload = (BranchSummaryPayload) brief.payload();
        assertThat(payload.details())
                .containsEntry("kind", "resume")
                .containsEntry("branchEntryId", fixture.rewindBrief().toString())
                .containsEntry("fromTreeSha", "tree-live-b")
                .containsEntry("toTreeSha", "tree-a2")
                .containsEntry("leftEntries", 5)
                .containsEntry("leftRuns", 1)
                .containsEntry("targetEntries", 4)
                .containsEntry("targetRuns", 2);
        assertThat(payload.summary()).contains("备注：先跑测试");
        assertThat(sessions.findById(sessionId).orElseThrow().leafEntryId())
                .isEqualTo(brief.id());
        assertThat(git.lastRestoreTarget).isEqualTo("tree-a2");
        assertThat(git.lastRestoreLatest).isEqualTo("tree-b1");
        assertThat(git.pins).contains(
                new FakeGit.Pin(sessionId.toString(), "branch-" + fixture.leftLeaf(),
                        "tree-live-b"),
                new FakeGit.Pin(sessionId.toString(), "branch-" + fixture.targetLeaf(),
                        "tree-a2"));
    }

    @Test
    void switchesBackSymmetric() {
        Fixture fixture = twoBranches();
        git.snapshots.add(new GitSnapshot("tree-live-b", "head", "main"));
        git.nextRestore = new GitRestoreResult(List.of("b.txt"), List.of());
        BranchResumeResult first = service.resume(sessionId, fixture.rewindBrief(), false, null);

        git.snapshots.add(new GitSnapshot("tree-live-a", "head", "main"));
        git.nextPlan = new GitRestorePlan("tree-live-b", "tree-a2", List.of("b.txt"),
                List.of());
        BranchResumeResult second = service.resume(sessionId, first.briefEntryId(), false, null);

        assertThat(second.switched()).isTrue();
        assertThat(second.targetLeafId()).isEqualTo(fixture.leftLeaf());
        SessionEntry brief = entries.findById(sessionId, second.briefEntryId()).orElseThrow();
        BranchSummaryPayload payload = (BranchSummaryPayload) brief.payload();
        assertThat(payload.details())
                .containsEntry("kind", "resume")
                .containsEntry("branchEntryId", first.briefEntryId().toString())
                .containsEntry("fromTreeSha", "tree-live-a")
                .containsEntry("toTreeSha", "tree-live-b");
        assertThat(sessions.findById(sessionId).orElseThrow().leafEntryId())
                .isEqualTo(brief.id());
    }

    @Test
    void conflictKeepsEverythingUntouched() {
        Fixture fixture = twoBranches();
        long before = entries.countBySession(sessionId);
        EntryId leafBefore = sessions.findById(sessionId).orElseThrow().leafEntryId();
        git.nextPlan = new GitRestorePlan("tree-a2", "tree-b1", List.of("b.txt"),
                List.of(new com.piagent.domain.git.model.GitConflict("b.txt", "又变了")));

        BranchResumeResult result = service.resume(sessionId, fixture.rewindBrief(), false, null);

        assertThat(result.restorable()).isFalse();
        assertThat(result.conflicts()).hasSize(1);
        assertThat(result.leftEntries()).isZero();
        assertThat(result.targetEntries()).isZero();
        assertThat(entries.countBySession(sessionId)).isEqualTo(before);
        assertThat(sessions.findById(sessionId).orElseThrow().leafEntryId()).isEqualTo(leafBefore);
        assertThat(git.restoreCalled).isFalse();
    }

    @Test
    void reportsUnavailableWhenTargetTreeMissing() {
        EntryId user = append(new MessagePayload(SessionEntryFactory.user("目标没有树")));
        EntryId brief = append(new BranchSummaryPayload(user, "切回没有树的分支",
                Map.of("kind", "resume", "toTreeSha", "tree-current")));
        append(new MessagePayload(SessionEntryFactory.user("当前有树")));
        append(new GitChangeSetPayload("run-c", 1, null, "tree-c0", "tree-current",
                List.of(), "diff", false, ""));

        BranchResumeResult result = service.resume(sessionId, brief, true, null);

        assertThat(result.available()).isFalse();
        assertThat(result.restorable()).isFalse();
        assertThat(result.unavailableReason()).contains("目标分支没有可用的工作区快照");
        assertThat(result.brief()).isEmpty();
    }

    @Test
    void rejectsTargetOnCurrentBranchAndNonBranchEntries() {
        Fixture fixture = twoBranches();
        EntryId plain = append(new MessagePayload(SessionEntryFactory.user("普通记录")));

        assertThatThrownBy(() -> service.resume(sessionId, plain, true, null))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.INVALID_BRANCH));
        assertThatThrownBy(() -> service.resume(sessionId, SessionEntryFactory.entryId(9999),
                true, null))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.INVALID_BRANCH));

        EntryId self = append(new BranchSummaryPayload(leaf, "指向当前叶子",
                Map.of("kind", "resume")));
        assertThatThrownBy(() -> service.resume(sessionId, self, true, null))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.INVALID_BRANCH));
    }

    @Test
    void rejectsRunningArchivedAndTooLongNote() {
        Fixture fixture = twoBranches();
        ChatSession running = sessions.findById(sessionId).orElseThrow().markRunning();
        sessions.update(running);
        assertThatThrownBy(() -> service.resume(sessionId, fixture.rewindBrief(), true, null))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.INVALID_STATE_TRANSITION));

        sessions.update(sessions.findById(sessionId).orElseThrow().markIdle().archive());
        assertThatThrownBy(() -> service.resume(sessionId, fixture.rewindBrief(), true, null))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.SESSION_ARCHIVED));

        sessions.update(sessions.findById(sessionId).orElseThrow());
    }

    @Test
    void rejectsTooLongNoteBeforePlanning() {
        Fixture fixture = twoBranches();
        assertThatThrownBy(() -> service.resume(sessionId, fixture.rewindBrief(), true,
                "x".repeat(201)))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.INVALID_ARGUMENT));
        assertThat(git.lastPlanTarget).isNull();
    }

    private Fixture twoBranches() {
        EntryId a1 = append(new MessagePayload(SessionEntryFactory.user("修复登录")));
        EntryId cp1 = append(new GitCheckpointPayload("run-a1", null, "tree-a0",
                "head-0", "main"));
        EntryId a2 = append(new MessagePayload(SessionEntryFactory.user("补测试")));
        EntryId cp2 = append(new GitCheckpointPayload("run-a2", a2, "tree-a1",
                "head-1", "main"));
        EntryId targetLeaf = append(new MessagePayload(SessionEntryFactory.user("继续")));
        EntryId rewindBrief = append(new BranchSummaryPayload(targetLeaf, "回退到第一次之前",
                Map.of("kind", "rewind", "fromTreeSha", "tree-a2", "toTreeSha", "tree-a0",
                        "discardedEntries", 4, "discardedRuns", 2)));
        append(new MessagePayload(SessionEntryFactory.user("新思路")));
        append(new GitCheckpointPayload("run-b1", null, "tree-a0", "head-0", "main"));
        append(new GitChangeSetPayload("run-b1", 1, null, "tree-a0", "tree-b1",
                List.of(), "diff", false, ""));
        EntryId leftLeaf = append(new MessagePayload(SessionEntryFactory.user("再改一点")));
        git.nextPlan = new GitRestorePlan("tree-a2", "tree-b1", List.of("b.txt"), List.of());
        git.nextRestore = new GitRestoreResult(List.of("b.txt"), List.of());
        return new Fixture(targetLeaf, leftLeaf, rewindBrief, entries.countBySession(sessionId));
    }

    private EntryId append(EntryPayload payload) {
        seq++;
        EntryId id = SessionEntryFactory.entryId(700 + (int) seq);
        SessionEntry entry = new SessionEntry(id, sessionId, seq, leaf, payload.entryType(),
                SessionEntry.CURRENT_PAYLOAD_VERSION, payload, 0, NOW.plusMillis(seq));
        entries.append(entry);
        leaf = id;
        sessions.update(sessions.findById(sessionId).orElseThrow().rewind(id));
        return id;
    }

    private record Fixture(EntryId targetLeaf, EntryId leftLeaf, EntryId rewindBrief,
            long entryCount) {
    }

    private static final class FakeGit implements WorkspaceGit {

        private final Deque<GitSnapshot> snapshots = new ArrayDeque<>();
        private final List<Pin> pins = new ArrayList<>();
        private GitRestorePlan nextPlan = new GitRestorePlan("tree", "tree", List.of(), List.of());
        private GitRestoreResult nextRestore = new GitRestoreResult(List.of(), List.of());
        private boolean restoreCalled;
        private String lastPlanTarget;
        private String lastPlanLatest;
        private String lastRestoreTarget;
        private String lastRestoreLatest;

        @Override
        public Optional<GitSnapshot> snapshot(Path workspace) {
            return Optional.ofNullable(snapshots.pollFirst());
        }

        @Override
        public GitChangeSet changes(Path workspace, String baseTreeSha, String treeSha) {
            return null;
        }

        @Override
        public GitRestoreResult restore(Path workspace, String baseTreeSha, String treeSha) {
            restoreCalled = true;
            lastRestoreTarget = baseTreeSha;
            lastRestoreLatest = treeSha;
            return nextRestore;
        }

        @Override
        public GitRestorePlan planRestore(Path workspace, String baseTreeSha, String treeSha) {
            lastPlanTarget = baseTreeSha;
            lastPlanLatest = treeSha;
            return nextPlan;
        }

        @Override
        public void pinCheckpoint(Path workspace, String namespace, String checkpointId,
                String treeSha) {
            pins.add(new Pin(namespace, checkpointId, treeSha));
        }

        @Override
        public void clearCheckpoints(Path workspace, String namespace) {
        }

        private record Pin(String namespace, String checkpointId, String treeSha) {
        }
    }
}
```

注意：`rejectsTargetOnCurrentBranch...` 中 `append` 会把 session 叶子移到新 entry，之后 `self` 的 `fromEntryId` 是 `leaf`（追加前），它已经变成 `self` 的祖先，因此目标在当前分支上，服务应拒绝。`rejectsRunning...` 后半段不要重复 `sessions.update` 同一版本；按上面代码操作即可。

- [ ] **Step 7: 跑测试**

Run: 通用验证命令中的应用层单测。
Expected: 全部通过。若 `BranchResumeBriefBuilderTest` 的 400 字断言失败，检查 `MAX_SUMMARY` 截断逻辑。

- [ ] **Step 8: 提交**

```bash
git add pi-java-application/src/main/java/com/piagent/application/timeline/ \
  pi-java-application/src/test/java/com/piagent/application/timeline/
git commit -m "feat(branch): switch to another branch and restore its workspace"
```

---

## Task M25-3：`POST /resume`

**Files:**
- Create: `pi-java-trigger/src/main/java/com/piagent/trigger/http/session/SessionResumeRequest.java`
- Create: `pi-java-trigger/src/main/java/com/piagent/trigger/http/session/SessionResumeResponse.java`
- Create: `pi-java-trigger/src/main/java/com/piagent/trigger/http/session/SessionResumeController.java`
- Create: `pi-java-trigger/src/test/java/com/piagent/trigger/http/session/SessionResumeControllerTest.java`
- Modify: `pi-java-app/src/test/java/com/piagent/app/openapi/OpenApiDocumentIntegrationTest.java`

- [ ] **Step 1: 写请求 DTO**

```java
package com.piagent.trigger.http.session;

import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;

/**
 * 分支切换请求。
 *
 * @param branchEntryId 当前分支上的分支简报
 * @param dryRun 是否只预览；缺省为 true
 * @param note 可选备注，最多 200 字
 */
public record SessionResumeRequest(String branchEntryId, Boolean dryRun, String note) {

    /**
     * 解析分支简报标识。
     *
     * @return 分支简报 entry
     */
    public EntryId requireBranchEntryId() {
        if (branchEntryId == null || branchEntryId.isBlank()) {
            throw new DomainException(DomainErrorCode.INVALID_ARGUMENT,
                    "branchEntryId 不能为空");
        }
        try {
            return EntryId.parse(branchEntryId);
        } catch (RuntimeException error) {
            throw new DomainException(DomainErrorCode.INVALID_ARGUMENT,
                    "branchEntryId 不是合法的 ULID");
        }
    }

    /**
     * 是否只预览；未提供时按预览处理。
     *
     * @return true 表示只预览
     */
    public boolean preview() {
        return dryRun == null || dryRun;
    }
}
```

- [ ] **Step 2: 写响应 DTO**

```java
package com.piagent.trigger.http.session;

import com.piagent.application.timeline.BranchResumeResult;
import com.piagent.domain.git.model.GitConflict;
import java.util.List;

/**
 * 分支切换预览或执行结果。
 *
 * @param dryRun 是否只是预览
 * @param switched 是否已完成切换
 * @param available 两侧树是否都可用
 * @param restorable 工作区是否可以安全还原
 * @param unavailableReason 不可用原因
 * @param targetLeafId 目标分支叶子
 * @param files 计划改动的路径
 * @param restored 实际改动的路径
 * @param conflicts 冲突列表
 * @param briefEntryId 新写入的切换简报；预览或失败时为 null
 * @param leftEntries 离开侧 entry 数
 * @param leftRuns 离开侧 run 数
 * @param targetEntries 目标侧 entry 数
 * @param targetRuns 目标侧 run 数
 * @param brief 简报正文
 */
public record SessionResumeResponse(boolean dryRun, boolean switched, boolean available,
        boolean restorable, String unavailableReason, String targetLeafId, List<String> files,
        List<String> restored, List<ConflictResponse> conflicts, String briefEntryId,
        int leftEntries, int leftRuns, int targetEntries, int targetRuns, String brief) {

    /**
     * 冲突文件。
     *
     * @param path 路径
     * @param reason 原因
     */
    public record ConflictResponse(String path, String reason) {
    }

    /**
     * 从应用层结果生成响应。
     *
     * @param result 切换结果
     * @return 响应
     */
    public static SessionResumeResponse from(BranchResumeResult result) {
        return new SessionResumeResponse(result.dryRun(), result.switched(), result.available(),
                result.restorable(), result.unavailableReason(),
                result.targetLeafId() == null ? null : result.targetLeafId().toString(),
                result.files(), result.restored(),
                result.conflicts().stream().map(SessionResumeResponse::conflict).toList(),
                result.briefEntryId() == null ? null : result.briefEntryId().toString(),
                result.leftEntries(), result.leftRuns(), result.targetEntries(),
                result.targetRuns(), result.brief());
    }

    private static ConflictResponse conflict(GitConflict conflict) {
        return new ConflictResponse(conflict.path(), conflict.reason());
    }
}
```

- [ ] **Step 3: 写控制器**

```java
package com.piagent.trigger.http.session;

import com.piagent.application.timeline.SessionBranchResumeService;
import com.piagent.domain.session.model.SessionId;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 分支切换控制器。
 *
 * <p>预览与执行共用同一接口，由 {@code dryRun} 区分；冲突与树不可用仍返回 200，
 * 只有分支关系非法、会话状态不允许或执行失败才返回 4xx/5xx。</p>
 */
@RestController
@RequestMapping("/api/v1/sessions")
public class SessionResumeController {

    private final SessionBranchResumeService resumes;

    /**
     * 创建控制器。
     *
     * @param resumes 切换用例
     */
    public SessionResumeController(SessionBranchResumeService resumes) {
        this.resumes = resumes;
    }

    /**
     * 预览或执行一次分支切换。
     *
     * @param id 会话 ID
     * @param request 切换请求
     * @return 预览或执行结果
     */
    @PostMapping("/{id}/resume")
    public SessionResumeResponse resume(@PathVariable String id,
            @RequestBody SessionResumeRequest request) {
        return SessionResumeResponse.from(resumes.resume(SessionId.parse(id),
                request.requireBranchEntryId(), request.preview(), request.note()));
    }
}
```

- [ ] **Step 4: 写 `SessionResumeControllerTest`**

```java
package com.piagent.trigger.http.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.piagent.application.timeline.BranchResumeResult;
import com.piagent.application.timeline.SessionBranchResumeService;
import com.piagent.domain.git.model.GitConflict;
import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.trigger.http.ApiExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 分支切换控制器测试：预览、执行、冲突、不可用与错误映射。 */
class SessionResumeControllerTest {

    private static final SessionId SESSION_ID = SessionId.parse("01TST000000000000000000001");
    private static final EntryId BRIEF = EntryId.parse("01TST000000000000000000002");
    private static final EntryId TARGET = EntryId.parse("01TST000000000000000000003");

    private final ObjectMapper json = new ObjectMapper();
    private SessionBranchResumeService resumes;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        resumes = mock(SessionBranchResumeService.class);
        mvc = MockMvcBuilders.standaloneSetup(new SessionResumeController(resumes))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void defaultsToPreviewAndReturnsFacts() throws Exception {
        when(resumes.resume(SESSION_ID, BRIEF, true, null)).thenReturn(preview());

        MvcResult result = mvc.perform(post("/api/v1/sessions/{id}/resume", SESSION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"branchEntryId\":\"" + BRIEF + "\"}"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = bodyOf(result);
        assertThat(body.path("dryRun").asBoolean()).isTrue();
        assertThat(body.path("available").asBoolean()).isTrue();
        assertThat(body.path("targetLeafId").asText()).isEqualTo(TARGET.toString());
        assertThat(body.path("files").get(0).asText()).isEqualTo("a.txt");
        assertThat(body.path("leftEntries").asInt()).isEqualTo(5);
        assertThat(body.path("targetRuns").asInt()).isEqualTo(2);
    }

    @Test
    void executesWhenDryRunIsFalse() throws Exception {
        when(resumes.resume(SESSION_ID, BRIEF, false, "先跑测试")).thenReturn(executed());

        MvcResult result = mvc.perform(post("/api/v1/sessions/{id}/resume", SESSION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"branchEntryId\":\"" + BRIEF
                                + "\",\"dryRun\":false,\"note\":\"先跑测试\"}"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = bodyOf(result);
        assertThat(body.path("switched").asBoolean()).isTrue();
        assertThat(body.path("briefEntryId").asText()).isNotNull();
        verify(resumes).resume(SESSION_ID, BRIEF, false, "先跑测试");
    }

    @Test
    void returnsConflictAndUnavailableWith200() throws Exception {
        when(resumes.resume(SESSION_ID, BRIEF, true, null)).thenReturn(conflict(), unavailable());

        JsonNode conflict = bodyOf(mvc.perform(post("/api/v1/sessions/{id}/resume", SESSION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"branchEntryId\":\"" + BRIEF + "\"}"))
                .andExpect(status().isOk()).andReturn());
        assertThat(conflict.path("restorable").asBoolean()).isFalse();
        assertThat(conflict.path("conflicts").get(0).path("path").asText())
                .isEqualTo("a.txt");
    }

    @Test
    void mapsInvalidBranchToConflict() throws Exception {
        when(resumes.resume(SESSION_ID, BRIEF, true, null))
                .thenThrow(new DomainException(DomainErrorCode.INVALID_BRANCH, "目标不是分支简报"));

        MvcResult result = mvc.perform(post("/api/v1/sessions/{id}/resume", SESSION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"branchEntryId\":\"" + BRIEF + "\"}"))
                .andExpect(status().isConflict())
                .andReturn();

        assertThat(bodyOf(result).path("code").asText()).isEqualTo("INVALID_BRANCH");
    }

    @Test
    void rejectsMissingBranchEntryId() throws Exception {
        mvc.perform(post("/api/v1/sessions/{id}/resume", SESSION_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    private static BranchResumeResult preview() {
        return new BranchResumeResult(true, false, true, true, "", TARGET, List.of("a.txt"),
                List.of(), List.of(), null, 5, 1, 8, 2, "切回另一条分支：…");
    }

    private static BranchResumeResult executed() {
        return new BranchResumeResult(false, true, true, true, "", TARGET, List.of("a.txt"),
                List.of("a.txt"), List.of(), EntryId.parse("01TST000000000000000000004"),
                5, 1, 8, 2, "切回另一条分支：…");
    }

    private static BranchResumeResult conflict() {
        return new BranchResumeResult(false, false, true, false, "", TARGET, List.of("a.txt"),
                List.of(), List.of(new GitConflict("a.txt", "又变了")), null, 5, 1, 8, 2,
                "切回另一条分支：…");
    }

    private static BranchResumeResult unavailable() {
        return new BranchResumeResult(false, false, false, false, "目标分支没有可用的工作区快照",
                TARGET, List.of(), List.of(), List.of(), null, 5, 1, 8, 2, "");
    }

    private JsonNode bodyOf(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }
}
```

注意 `returnsConflictAndUnavailableWith200` 里连续 `when(...).thenReturn(conflict(), unavailable())` 只对两次调用有效；第二次调用返回 unavailable。测试只断言 conflict 是够的，unavailable 单独加一个请求即可。简化：删除 `unavailable()` 的连续返回，单测只验证冲突；不可用路径由应用层单测覆盖。

- [ ] **Step 5: 更新 OpenAPI 断言**

在 `OpenApiDocumentIntegrationTest` 的 `paths` 断言处补：

```java
assertThat(paths.has("/api/v1/sessions/{id}/resume")).isTrue();
```

- [ ] **Step 6: 跑测试**

Run: `mvn -q -pl pi-java-trigger -am -Dtest=SessionResumeControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`
再跑 `mvn -q -pl pi-java-app -am -Dtest=OpenApiDocumentIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`。
Expected: 通过。

- [ ] **Step 7: 提交**

```bash
git add pi-java-trigger/src/main/java/com/piagent/trigger/http/session/SessionResume*.java \
  pi-java-trigger/src/test/java/com/piagent/trigger/http/session/SessionResumeControllerTest.java \
  pi-java-app/src/test/java/com/piagent/app/openapi/OpenApiDocumentIntegrationTest.java
git commit -m "feat(api): expose branch resume endpoint"
```

---

## Task M25-4：时间线 `RESUME` 与对比泛化

**Files:**
- Modify: `pi-java-application/src/main/java/com/piagent/application/timeline/SessionTimeline.java`
- Modify: `pi-java-application/src/main/java/com/piagent/application/timeline/SessionTimelineService.java`
- Modify: `pi-java-application/src/main/java/com/piagent/application/timeline/SessionBranchComparison.java`
- Modify: `pi-java-application/src/main/java/com/piagent/application/timeline/SessionBranchComparisonService.java`
- Modify: `pi-java-application/src/main/java/com/piagent/application/timeline/BranchComparisonProjector.java`
- Modify: `pi-java-trigger/src/main/java/com/piagent/trigger/http/session/SessionTimelineResponse.java`
- Modify: `pi-java-trigger/src/main/java/com/piagent/trigger/http/session/SessionComparisonController.java`
- Modify: `pi-java-trigger/src/main/java/com/piagent/trigger/http/session/SessionComparisonResponse.java`
- Modify: `pi-java-trigger/src/main/java/com/piagent/trigger/cli/CliTimelineSupport.java`
- Modify: `pi-java-trigger/src/main/java/com/piagent/trigger/cli/CliTimelineText.java`
- Modify: 对应测试

- [ ] **Step 1: 改 `SessionTimeline`**

```java
public enum NodeKind {
    RUN,
    REWIND,
    RESUME
}
```

`Node` 最后一个字段改为：

```java
BranchEvidence branch
```

并替换 `RewindEvidence` 为：

```java
/**
 * 分支节点的简报。
 *
 * @param entryId 简报 entry，用于对比或切换时指定这一次
 * @param brief 摘要正文
 * @param leftEntries 离开侧 entry 数
 * @param leftRuns 离开侧 run 数
 */
public record BranchEvidence(EntryId entryId, String brief, int leftEntries, int leftRuns) {
}
```

- [ ] **Step 2: 改 `SessionTimelineService`**

把 `isRewind` 替换为 `isBranch`：

```java
private boolean isBranch(BranchSummaryPayload payload) {
    Object kind = payload.details().get("kind");
    return "rewind".equals(kind) || "resume".equals(kind);
}
```

两个节点收集循环都改成：

```java
} else if (payload instanceof BranchSummaryPayload summary && isBranch(summary)) {
    nodes.add(new IndexedNode(index, branchNode(branch.get(index), summary)));
}
```

`rewindNode` 替换为：

```java
private SessionTimeline.Node branchNode(SessionEntry entry, BranchSummaryPayload payload) {
    Map<String, Object> details = payload.details();
    SessionTimeline.NodeKind kind = "resume".equals(details.get("kind"))
            ? SessionTimeline.NodeKind.RESUME : SessionTimeline.NodeKind.REWIND;
    return new SessionTimeline.Node(kind, null, text(details.get("runId")),
            entry.createdAt(), false, false, null,
            new SessionTimeline.BranchEvidence(entry.id(), payload.summary(),
                    count(details, "leftEntries", "discardedEntries"),
                    count(details, "leftRuns", "discardedRuns")));
}

private int count(Map<String, Object> details, String key, String legacy) {
    Object value = details.get(key);
    return number(value == null ? details.get(legacy) : value);
}
```

所有 `node.rewind()` 改为 `node.branch()`。

- [ ] **Step 3: 改对比模型与服务**

`SessionBranchComparison` 字段 `rewindEntryId` → `branchEntryId`，Javadoc 同步；`unavailable()` 构造参数顺序不变。

`SessionBranchComparisonService`：

```java
public SessionBranchComparison compare(SessionId sessionId, EntryId branchEntryId) {
```

筛选：

```java
List<SessionEntry> briefs = current.stream()
        .filter(entry -> entry.payload() instanceof BranchSummaryPayload summary
                && isBranch(summary))
        .toList();
```

`select` 参数改名，错误文案：

```java
"指定的分支简报不在当前分支上: " + branchEntryId
```

`isRewind` 改 `isBranch`（同时间线）。

`BranchComparisonProjector` 摘要改中性措辞：

```java
StringBuilder text = new StringBuilder(describe("另一条分支", abandoned));
text.append('\n').append(describe("当前分支", current));
appendPaths(text, "另一条分支独有的文件：", files.onlyAbandoned());
appendPaths(text, "当前分支独有的文件：", files.onlyCurrent());
```

- [ ] **Step 4: 改 HTTP DTO**

`SessionTimelineResponse.NodeResponse` 删除 `rewindEntryId`、`discardedEntries`、`discardedRuns`，加入：

```java
String branchEntryId,
...
String brief, int leftEntries, int leftRuns, EvidenceView evidence
```

`node(...)` 映射改为：

```java
SessionTimeline.BranchEvidence branch = node.branch();
...
branch == null ? null : id(branch.entryId()),
...
branch == null ? "" : branch.brief(),
branch == null ? 0 : branch.leftEntries(),
branch == null ? 0 : branch.leftRuns(),
```

`SessionComparisonResponse` 字段 `rewindEntryId` → `branchEntryId`，`from` 里同步。

`SessionComparisonController`：

```java
public SessionComparisonResponse comparison(@PathVariable String id,
        @RequestParam(required = false) String branch,
        @RequestParam(required = false) String rewind) {
    String raw = branch == null || branch.isBlank() ? rewind : branch;
    EntryId entryId = raw == null || raw.isBlank() ? null : EntryId.parse(raw);
    return SessionComparisonResponse.from(comparisons.compare(SessionId.parse(id), entryId));
}
```

- [ ] **Step 5: 先改 CLI 编译引用（`/resume` 留到 M25-6）**

- `target.rewind()` → `target.branch()`。
- `rewindNode` 改名为 `branchNode`，接受 `NodeKind != RUN`，错误文案“不是分支节点”。
- `compare` 用 `branchNode`。
- `nodeLine`：

```java
String label = switch (node.kind()) {
    case RUN -> "run";
    case REWIND -> "回退";
    case RESUME -> "切回";
};
String head = mark + " " + number + ". " + TIME.format(node.at()) + " · " + label;
if (node.kind() != SessionTimeline.NodeKind.RUN) {
    return head + " · " + CliText.oneLine(node.branch().brief(), MAX_INTENT);
}
```

- `CliTimelineText.compare` 标题“对比 · 另一条分支 vs 当前分支”，`printSide("另一条分支", ...)`，文件标签“仅另一条分支”/“仅当前分支”。
- 测试同步：`RewindEvidence` → `BranchEvidence`，`rewind()` → `branch()`，`rewindEntryId()` → `branchEntryId()`，旧分支文案改“另一条分支”。

- [ ] **Step 6: 补测试**

- `SessionTimelineServiceTest`：把现有 rewind 断言改为 `branch()` / `leftEntries`；新增：

```java
@Test
void projectsResumeNodeWithBranchEvidence() {
    append(new MessagePayload(new UserMessage(List.of(new TextBlock("旧分支")), NOW)));
    append(new GitCheckpointPayload("run-1", null, "tree-0", "head", "main"));
    EntryId oldLeaf = leaf;
    append(new BranchSummaryPayload(oldLeaf, "回退简报", Map.of("kind", "rewind",
            "leftEntries", 2, "leftRuns", 1)));
    EntryId target = null;
    append(new BranchSummaryPayload(oldLeaf, "切回另一条分支", Map.of("kind", "resume",
            "branchEntryId", oldLeaf.toString(), "leftEntries", 4, "leftRuns", 2)));
    moveLeaf();

    SessionTimeline timeline = service.timeline(sessionId);

    SessionTimeline.Node last = timeline.nodes().getLast();
    assertThat(last.kind()).isEqualTo(SessionTimeline.NodeKind.RESUME);
    assertThat(last.current()).isTrue();
    assertThat(last.branch().brief()).isEqualTo("切回另一条分支");
    assertThat(last.branch().leftEntries()).isEqualTo(4);
    assertThat(last.branch().leftRuns()).isEqualTo(2);
    assertThat(timeline.currentCheckpointEntryId()).isNull();
}
```

（`target` 变量删掉。）

- `SessionBranchComparisonServiceTest`：新增一条 `kind=resume` 简报的对比测试，选择用 `branchEntryId`；旧 rewind 简报仍可用。
- `SessionTimelineControllerTest`：JSON 字段改为 `branchEntryId` / `leftEntries` / `leftRuns`，新增一个 `type=RESUME` 的节点响应断言。
- `SessionComparisonControllerTest`：字段 `branchEntryId`；新增 `?branch=` 参数测试，保留 `?rewind=` 别名测试。
- `BranchComparisonProjectorTest` 与 `BranchComparisonIntegrationTest` 文案改“另一条分支”。
- `CliTimelineSupportTest`：分支证据与文案更新；新增 `/compare` 接受 RESUME 节点的用例。

- [ ] **Step 7: 跑测试**

Run 应用层 + 触发层通用验证命令。
Expected: 全部通过。

- [ ] **Step 8: 提交**

```bash
git add pi-java-application/src/main/java/com/piagent/application/timeline/ \
  pi-java-application/src/test/java/com/piagent/application/timeline/ \
  pi-java-trigger/src/main/java/com/piagent/trigger/ \
  pi-java-trigger/src/test/java/com/piagent/trigger/
git commit -m "feat(timeline): project resume nodes and compare both branch kinds"
```

---

## Task M25-5：Web 分支卡片、切换预览与对比入口

**Files:**
- Modify: `pi-java-app/src/main/resources/static/js/api.js`
- Modify: `pi-java-app/src/main/resources/static/js/timeline.js`
- Modify: `pi-java-app/src/main/resources/static/js/turn-view.js`
- Modify: `pi-java-app/src/main/resources/static/js/store.js`
- Modify: `pi-java-app/src/main/resources/static/index.html`
- Modify: `pi-java-app/src/main/resources/static/css/timeline.css`

- [ ] **Step 1: `api.js`**

`sessionComparison` 第二个参数改名 `branchEntryId`，查询参数用 `?branch=`：

```js
export function sessionComparison(sessionId, branchEntryId) {
    const query = branchEntryId
        ? `?branch=${encodeURIComponent(branchEntryId)}` : "";
    return request(`/api/v1/sessions/${encodeURIComponent(sessionId)}/comparison${query}`);
}
```

新增：

```js
/**
 * 预览或执行一次分支切换。
 *
 * @param {string} sessionId 会话 ID
 * @param {{branchEntryId: string, dryRun: boolean, note?: string}} payload 切换请求
 * @returns {Promise<object>} 预览或执行结果
 */
export function resumeBranch(sessionId, payload) {
    return request(`/api/v1/sessions/${encodeURIComponent(sessionId)}/resume`,
        jsonInit("POST", payload));
}
```

- [ ] **Step 2: `store.js`**

新增：

```js
compareResume: document.getElementById("compare-resume"),
```

- [ ] **Step 3: `index.html`**

- 对比左侧标题改为“另一条分支”。
- 对比弹窗底部加：

```html
<footer class="compare-actions">
    <button id="compare-resume" class="primary-button hidden" type="button">
        切到另一条分支
    </button>
</footer>
```

- 预览备注文案改为“备注（可选，会写进分支简报）”。
- 被放弃记录弹窗 lead 的默认文案改为“只读查看，不会加入当前上下文。”，由 JS 按切换/回退更新。

- [ ] **Step 4: `timeline.js` 分支卡片**

`renderNode` 的 `else` 分支替换为：

```js
} else {
    const isResume = node.type === "RESUME";
    card.append(el("p", "timeline-brief", node.brief || "（无简报）"),
        el("span", "timeline-meta",
            `离开 ${node.leftEntries || 0} 条记录 · `
            + `${node.leftRuns || 0} 次运行`));
    if (node.branchEntryId) {
        const actions = el("div", "timeline-branch-actions");
        const compare = el("button", "timeline-compare", "对比两条分支");
        compare.type = "button";
        compare.dataset.compare = node.branchEntryId;
        const resume = el("button", "timeline-resume",
            isResume ? "切到另一条分支" : "切回这条分支");
        resume.type = "button";
        resume.dataset.resume = node.branchEntryId;
        actions.append(compare, resume);
        card.append(actions);
    }
}
```

`renderSpine`：

```js
if (node.type === "REWIND" || node.type === "RESUME") {
    const mark = el("span", "timeline-tick is-rewind"
        + (node.type === "RESUME" ? " is-resume" : ""));
    ...
}
```

`nodeTitle`：

```js
if (node.type === "REWIND") {
    return `在 ${formatTime(node.at)} 回溯`;
}
if (node.type === "RESUME") {
    return `在 ${formatTime(node.at)} 切回`;
}
```

`onListClick` 增加：

```js
const resume = event.target.closest("button[data-resume]");
if (resume) {
    previewBranch(resume.dataset.resume);
    return;
}
```

- [ ] **Step 5: 切换预览**

状态新增：

```js
let previewMode = "rewind";
let selectedBranch = null;
```

新增 `previewBranch`：

```js
async function previewBranch(branchEntryId) {
    const sessionId = state.session?.id;
    if (!sessionId || !branchEntryId
            || (phase !== "ready" && phase !== "previewing")) {
        return;
    }
    const token = ++previewId;
    previewMode = "resume";
    selectedBranch = branchEntryId;
    selectedCheckpoint = null;
    previewResult = null;
    phase = "previewing";
    openPreview();
    dom.timelinePreviewTitle.textContent = "切到另一条分支";
    dom.timelinePreviewKicker.textContent = "切换预览";
    dom.timelinePreviewBody.replaceChildren(el("p", "timeline-preview-loading",
        "正在计算要改动的文件…"));
    setPreviewError("");
    dom.timelineNote.value = "";
    dom.timelineConfirm.disabled = true;
    try {
        const result = await api.resumeBranch(sessionId, {
            branchEntryId,
            dryRun: true
        });
        if (token !== previewId || state.session?.id !== sessionId) {
            return;
        }
        previewResult = result;
        renderPreview(result);
    } catch (error) {
        if (token !== previewId) {
            return;
        }
        phase = "ready";
        closePreview(true);
        toast(error.message || "无法预览切换", true);
    }
}
```

`preview()` 开头设置：

```js
previewMode = "rewind";
selectedBranch = null;
```

`renderPreview(result)` 按模式渲染：

```js
if (previewMode === "resume") {
    const node = nodeOfBranch(selectedBranch);
    dom.timelinePreviewTitle.textContent = node?.at
        ? `切到 ${formatTime(node.at)} 的另一条分支`
        : "切到另一条分支";
    if (!result.available) {
        dom.timelinePreviewKicker.textContent = "无法切换";
    } else {
        dom.timelinePreviewKicker.textContent =
            result.restorable ? "切换预览" : "存在冲突";
    }
    const facts = el("div", "timeline-preview-facts");
    facts.append(el("span", "", `离开 ${result.leftEntries || 0} 条记录`),
        el("span", "", `离开 ${result.leftRuns || 0} 次运行`),
        el("span", "", `改动 ${(result.files || []).length} 个文件`),
        el("span", "", `目标 ${result.targetEntries || 0} 条记录`),
        el("span", "", `目标 ${result.targetRuns || 0} 次运行`));
    body.append(facts);
    if (!result.available && result.unavailableReason) {
        body.append(el("p", "timeline-preview-unavailable", result.unavailableReason));
    }
    const conflictTitle = "这些文件在目标之后又被修改，切换被整体放弃：";
    // 文件、冲突与回退共用同一段渲染，只替换标题。
} else {
    // 现有回退逻辑，用 selectedCheckpoint 找 node。
}
```

为避免重复代码，把“简报 / facts / 文件 / 冲突”抽成 `renderBranchPreviewBody(result, mode)`；回退与切换只在标题、kicker、facts 文案和冲突标题上不同。确认按钮：

```js
dom.timelineConfirm.disabled = !result.available || !result.restorable;
dom.timelineConfirm.textContent = !result.available
    ? "无法切换"
    : result.restorable
        ? (previewMode === "resume" ? "确认切换" : "确认回退")
        : (previewMode === "resume" ? "无法切换" : "无法回退");
```

新增：

```js
function nodeOfBranch(branchEntryId) {
    return (timeline?.nodes || []).find((node) =>
        node.branchEntryId === branchEntryId) || null;
}
```

- [ ] **Step 6: 确认与关闭**

把 `confirmRewind` 改名为 `confirmPreview`，内部按模式分支：

```js
const isResume = previewMode === "resume";
const payload = isResume
    ? { branchEntryId: selectedBranch, dryRun: false, note }
    : { checkpointEntryId: selectedCheckpoint, dryRun: false, note };
const result = isResume
    ? await withTimeout(api.resumeBranch(sessionId, payload), REWIND_TIMEOUT_MS)
    : await withTimeout(api.rewindSession(sessionId, payload), REWIND_TIMEOUT_MS);
const done = isResume ? result.switched : result.rewound;
dom.timelinePreviewKicker.textContent = isResume ? "正在切换" : "正在回退";
dom.timelinePreviewBody.replaceChildren(el("p", "timeline-preview-loading",
    isResume ? "正在还原文件并切换分支…" : "正在还原文件并把对话退回检查点…"));
if (!done) { ... }
toast(isResume
    ? `已切到另一条分支，改动 ${(result.restored || []).length} 个文件`
    : `已回退，还原 ${(result.restored || []).length} 个文件`);
```

`closePreview` 重置：

```js
dom.timelineConfirm.textContent = previewMode === "resume" ? "确认切换" : "确认回退";
dom.timelinePreviewKicker.textContent =
    previewMode === "resume" ? "切换预览" : "回退预览";
selectedBranch = null;
previewMode = "rewind";
```

表单提交与按钮事件保留，只改调用名。

- [ ] **Step 7: 对比对话框**

`renderCompare`：

```js
dom.compareSummary.textContent = "这个会话还没有分支切换记录，暂时没有可对比的分支。";
```

并控制按钮：

```js
dom.compareResume?.classList.toggle("hidden", !value?.branchEntryId);
```

（`available` 时显示；unavailable 时隐藏。）

`renderCompareFiles` 标签：

```js
["仅另一条分支", files.onlyAbandoned],
["仅当前分支", files.onlyCurrent],
["两边都改过", files.both]
```

`openCompare(branchEntryId)` 变量改名。对比按钮点击：

```js
dom.compareResume?.addEventListener("click", () => {
    const branchEntryId = compareBranchId;
    closeCompare();
    if (branchEntryId) {
        previewBranch(branchEntryId);
    }
});
```

在 `renderCompare(value)` 里保存 `compareBranchId = value?.branchEntryId || null`。

- [ ] **Step 8: 切换简报渲染**

`turn-view.js`：

- `rewindNotes` 改名 `branchNotes`；条件改为：

```js
} else if (entry.type === "BRANCH_SUMMARY"
        && ["rewind", "resume"].includes(entry.body?.extra?.details?.kind)) {
    branchNotes.push(renderNote(entry));
}
```

- `renderNote` 的 `BRANCH_SUMMARY` 分支：

```js
if (entry.type === "BRANCH_SUMMARY") {
    const kind = entry.body?.extra?.details?.kind;
    if (kind === "rewind" || kind === "resume") {
        return branchNote(entry, kind);
    }
    ...
}
```

- 把 `rewindNote` 改名为 `branchNote(entry, kind)`，开头按 kind 改标签：

```js
const isResume = kind === "resume";
node.querySelector(".history-note-title").textContent =
    isResume ? "切换简报" : "回溯简报";
node.querySelector(".history-note-state").textContent =
    isResume ? "已换到这条路" : "从这里继续";
node.querySelector(".rewind-note-summary").textContent =
    extra.summary || (isResume ? "切换简报" : "回溯简报");
```

facts 按 kind：

```js
if (isResume) {
    if (Number(details.leftEntries) > 0) {
        facts.push(`离开 ${details.leftEntries} 条记录`);
    }
    if (Number(details.leftRuns) > 0) {
        facts.push(`${details.leftRuns} 次运行`);
    }
    if (Number(details.restoredFiles) > 0) {
        facts.push(`改动 ${details.restoredFiles} 个文件`);
    }
    if (Number(details.deniedApprovals) > 0) {
        facts.push(`${details.deniedApprovals} 次审批被拒`);
    }
    if (Number(details.failedVerifications) > 0) {
        facts.push(`${details.failedVerifications} 次验证失败`);
    }
} else {
    // 现有回退 facts
}
```

历史按钮：

```js
historyButton.textContent = isResume ? "查看离开的分支" : "查看被放弃的记录";
historyButton.dataset.rewindKind = kind;
historyButton.dataset.rewindCount =
    String(isResume ? (details.leftEntries || 0) : (details.discardedEntries || 0));
```

历史弹窗 lead 在 `updateHistoryChrome` 中按 `data-rewind-kind` 显示“这次切换离开了 X 条记录。”或保持回退文案；在 `onHistoryClick` 读取时把 kind 存入 `historyView`。

- [ ] **Step 9: 样式**

`timeline.css` 新增：

```css
.timeline-branch-actions {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    margin-top: 10px;
}

.timeline-resume {
    /* 与 .timeline-compare 相同的按钮基线，用 teal/绿色信号色区分切换 */
}

.timeline-tick.is-resume {
    background: var(--signal-resume, #39b7a4);
}

.timeline-preview-unavailable {
    margin: 12px 0;
    color: var(--warn-text);
    line-height: 1.6;
}
```

具体颜色沿用现有变量；若没有对应变量，用现有成功色变量，不引入新色系。

- [ ] **Step 10: 前端静态检查与浏览器验证**

Run: `mvn -q -pl pi-java-app -am -DskipTests package` 确认资源打包通过。
启动应用（端口 8080 若被用户占用，先用 8081），用 Playwright 打开：
1. 新建会话，`/write` 两轮，打开时间线；
2. 回退一次，继续一轮，点击“切回这条分支”；
3. 验证预览文件列表、确认切换、时间线出现“切回”节点、会话出现“切换简报”；
4. 打开对比，点击“切到另一条分支”，确认可再切回；
5. 桌面 1440×960 与移动 390×844 截图，控制台无错误。

- [ ] **Step 11: 提交**

```bash
git add pi-java-app/src/main/resources/static/
git commit -m "feat(web): switch between branches from timeline and compare"
```

---

## Task M25-6：CLI `/resume`

**Files:**
- Modify: `pi-java-trigger/src/main/java/com/piagent/trigger/cli/CliTimelineSupport.java`
- Modify: `pi-java-trigger/src/main/java/com/piagent/trigger/cli/CliTimelineText.java`
- Modify: `pi-java-trigger/src/main/java/com/piagent/trigger/cli/CliChatSupport.java`
- Modify: `pi-java-trigger/src/main/java/com/piagent/trigger/cli/SlashCompleter.java`
- Modify: `pi-java-trigger/src/test/java/com/piagent/trigger/cli/CliTimelineSupportTest.java`

- [ ] **Step 1: 注入切换用例**

构造函数增加 `SessionBranchResumeService resumes`，字段同步。Javadoc 补参数。`CliChatSupport` 由 Spring 自动注入，无需改构造参数顺序之外的代码。

- [ ] **Step 2: 实现 `resume`**

```java
/**
 * 切到时间线上的另一条分支。
 *
 * @param session 当前会话
 * @param argument 时间线序号；空表示最近一条分支简报
 * @param terminal 输出终端
 * @return 切换后的会话；取消或拒绝时返回原会话
 */
public ChatSession resume(ChatSession session, String argument, CliTerminal terminal) {
    SessionTimeline timeline = timelines.timeline(session.identity().id());
    SessionTimeline.Node target = branchNode(timeline, argument, terminal);
    if (target == null) {
        return session;
    }
    BranchResumeResult preview = resumes.resume(session.identity().id(),
            target.branch().entryId(), true, null);
    CliTimelineText.resumePreview(preview, terminal);
    if (!preview.available() || !preview.restorable()) {
        return session;
    }
    if (!terminal.interactive()) {
        terminal.println("当前不是交互终端，已取消切换；请在 chat 中手动执行");
        return session;
    }
    String answer = terminal.readLine("确认切换？输入 y 回车执行，直接回车取消: ");
    if (!confirmed(answer)) {
        terminal.println("已取消切换");
        return session;
    }
    BranchResumeResult result = resumes.resume(session.identity().id(),
            target.branch().entryId(), false, null);
    if (!result.switched()) {
        terminal.println("切换没有执行，工作区可能又被改动，请重试");
        return session;
    }
    terminal.println("已切换 · 改动 " + result.restored().size() + " 个文件");
    result.restored().forEach(path -> terminal.println("  - " + path));
    return sessions.require(session.identity().id().toString());
}
```

`branchNode` 在参数为空时取最后一条 `REWIND/RESUME`；有参数时序号必须指向分支节点。

- [ ] **Step 3: 排版**

`CliTimelineText.timeline` 末尾提示：

```java
terminal.println("  /compare [序号] 对比两条分支 · /rewind <序号> 回退到检查点"
        + " · /resume [序号] 切到另一条分支");
```

新增：

```java
static void resumePreview(BranchResumeResult preview, CliTerminal terminal) {
    terminal.println("将切换 · 离开 " + preview.leftEntries() + " 条记录 / "
            + preview.leftRuns() + " 个 run · 目标分支 " + preview.targetEntries()
            + " 条记录 / " + preview.targetRuns() + " 个 run · 改动 "
            + preview.files().size() + " 个文件");
    if (!preview.available()) {
        terminal.println(preview.unavailableReason());
        return;
    }
    if (!preview.brief().isBlank()) {
        terminal.println(CliText.oneLine(preview.brief(), MAX_BRIEF));
    }
    preview.files().forEach(path -> terminal.println("  - " + path));
    for (GitConflict conflict : preview.conflicts()) {
        terminal.println("  ! " + conflict.path() + " · " + conflict.reason());
    }
    if (!preview.restorable()) {
        terminal.println("存在冲突，不能切换；请先处理上面的文件");
    }
}
```

`nodeLine` 已区分“回退”/“切回”（M25-4）。

- [ ] **Step 4: 命令注册**

- `CliChatSupport` 命令列表加 `"/resume"`，dispatch 加：

```java
case "/resume" -> {
    return timeline.resume(session, argument, terminal);
}
```

- help 增加：

```text
/resume [N]    切到另一条分支（需确认）
```

- `SlashCompleter.COMMANDS` 加 `"/resume"`。
- TUI 的提示若硬编码命令列表，同步加 `/resume`。

- [ ] **Step 5: 测试**

`CliTimelineSupportTest` 构造加 mock `SessionBranchResumeService`，新增：
- `/resume` 预览后确认执行，断言重新加载会话；
- 非交互直接拒绝且不调用执行；
- 预览 `available=false` 时不确认；
- `/compare` 接受 `RESUME` 节点。

Run: `mvn -q -pl pi-java-trigger -am -Dtest=CliTimelineSupportTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: 通过。

- [ ] **Step 6: 手工验收**

在临时工作区启动 CLI，跑 `/timeline`、`/compare`、`/resume`，确认非交互模式拒绝切换。

- [ ] **Step 7: 提交**

```bash
git add pi-java-trigger/src/main/java/com/piagent/trigger/cli/ \
  pi-java-trigger/src/test/java/com/piagent/trigger/cli/
git commit -m "feat(cli): add /resume branch switching"
```

---

## Task M25-7：集成测试、文档与全量验证

**Files:**
- Create: `pi-java-app/src/test/java/com/piagent/app/timeline/BranchResumeIntegrationTest.java`
- Create: `docs/milestones/M25.md`
- Modify: `docs/05-api-and-cli.md`
- Modify: `docs/11-interface-design.md`
- Modify: `docs/12-git.md`
- Modify: `docs/08-roadmap.md`
- Modify: `README.md`

- [ ] **Step 1: 写集成测试**

复用 `BranchComparisonIntegrationTest` 的 Fake demo 与真实 Git 设置，核心方法：

```java
@Test
void switchesBackAndForthWithWorkspaceAndContext() throws IOException {
    ChatSession session = sessions.createSession(
            new CreateSessionCommand("分支切回", workspace, MODEL_ID, ThinkingLevel.OFF));
    SessionId sessionId = session.identity().id();
    run(sessionId, "/write old.txt 旧分支");
    run(sessionId, "/write shared.txt 旧共享");

    SessionTimeline before = timelines.timeline(sessionId);
    SessionRewindResult rewind = rewinds.rewind(sessionId,
            before.nodes().get(1).checkpointEntryId(), false, "换一种写法");
    assertThat(rewind.rewound()).isTrue();
    assertThat(Files.exists(workspace.resolve("shared.txt"))).isFalse();

    run(sessionId, "/write shared.txt 新共享");
    run(sessionId, "/write new.txt 新分支");

    BranchResumeResult preview = resumes.resume(sessionId, rewind.briefEntryId(), true, null);
    assertThat(preview.available()).isTrue();
    assertThat(preview.restorable()).isTrue();
    assertThat(preview.files()).anyMatch(path -> path.endsWith("shared.txt"));

    BranchResumeResult switched = resumes.resume(sessionId, rewind.briefEntryId(), false,
            "先跑测试");
    assertThat(switched.switched()).isTrue();
    assertThat(Files.readString(workspace.resolve("shared.txt"))).isEqualTo("旧共享");
    assertThat(Files.exists(workspace.resolve("new.txt"))).isFalse();
    assertThat(Files.exists(workspace.resolve("old.txt"))).isTrue();

    List<SessionEntry> branch = currentBranch(sessionId);
    assertThat(branch.getLast().id()).isEqualTo(switched.briefEntryId());
    SessionEntry brief = branch.getLast();
    assertThat(brief.payload()).isInstanceOfSatisfying(BranchSummaryPayload.class,
            payload -> {
                assertThat(payload.details().get("kind")).isEqualTo("resume");
                assertThat(payload.summary()).contains("先跑测试");
            });
    SessionContextProjector projector = new SessionContextProjector();
    assertThat(projector.project(branch, branch.getLast().id()).messages())
            .hasAtLeastOneElementOfType(BranchSummaryMessage.class);

    SessionTimeline resumed = timelines.timeline(sessionId);
    assertThat(resumed.nodes().getLast().kind())
            .isEqualTo(SessionTimeline.NodeKind.RESUME);

    BranchResumeResult back = resumes.resume(sessionId, switched.briefEntryId(), false, null);
    assertThat(back.switched()).isTrue();
    assertThat(Files.readString(workspace.resolve("shared.txt"))).isEqualTo("新共享");
    assertThat(Files.exists(workspace.resolve("new.txt"))).isTrue();
    assertThat(Files.exists(workspace.resolve("old.txt"))).isFalse();

    SessionBranchComparison comparison = comparisons.compare(sessionId, back.briefEntryId());
    assertThat(comparison.available()).isTrue();
    assertThat(comparison.branchEntryId()).isEqualTo(back.briefEntryId());
}
```

需要 autowire：`SessionBranchResumeService resumes`、`SessionBranchComparisonService comparisons`、`SessionRepository`、`SessionEntryStore`，以及 `SessionBranchComparison`、`BranchResumeResult`、`BranchSummaryMessage`、`SessionEntry` 等导入。`run` / `currentBranch` / `git` / `deleteRecursively` 从 `DecisionTimelineIntegrationTest` 复制。

如果 Fake demo 的 `/write` 文本不精确匹配文件内容，先运行现有 `BranchComparisonIntegrationTest` 确认写入内容规则，再调整断言（只断言文件存在与关键片段）。

- [ ] **Step 2: 跑集成测试**

Run: `mvn -q -pl pi-java-app -am -Dtest=BranchResumeIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: 通过。

- [ ] **Step 3: 更新现有集成测试**

`BranchComparisonIntegrationTest`：
- `comparison.rewindEntryId()` → `comparison.branchEntryId()`；
- 摘要断言“旧分支” → “另一条分支”；
- 可补一处 `kind=resume` 的对比断言（如果测试流程里已产生 resume 简报）。

- [ ] **Step 4: 文档**

`docs/milestones/M25.md` 至少包含：
- 背景与目标；
- 实现清单（树证据、切换用例、API、时间线/对比、Web、CLI）；
- 安全与一致性（冲突整体放弃、事务顺序、保活 ref）；
- 测试结果（单测数量、`mvn -q verify` 结果、浏览器/CLI 实测结论）；
- 已知限制（逐文件写入中断可能残留、旧简报无树时不可切换）。

`docs/05-api-and-cli.md`：
- 增加 `POST /api/v1/sessions/{id}/resume` 请求/响应与状态码表；
- 时间线字段 `branchEntryId` / `leftEntries` / `leftRuns`；
- 对比参数 `branch`（`rewind` 为别名）与响应 `branchEntryId`；
- CLI `/resume`。

`docs/11-interface-design.md`：
- 分支卡片双按钮、切换预览、对比入口、切换简报。

`docs/12-git.md`：
- 树证据优先级（位置优先）、`fromTreeSha`/`toTreeSha`、`branch-<leaf>` 保活 ref。

`docs/08-roadmap.md`：
- 状态行 M0–M25；
- M25 行从“未实施/待设计”改为已完成，补测试数与提交信息。

`README.md`：
- 核心能力表补“分支切换：在两条已走过的路之间来回继续，工作区与上下文一起还原”。

- [ ] **Step 5: 全量验证**

Run: `mvn -q verify`
Expected: BUILD SUCCESS；测试数从 948 增加；Checkstyle/ArchUnit/覆盖率通过。

- [ ] **Step 6: 浏览器与 CLI 最终走查**

- Web：桌面 1440×960、移动 390×844；切换预览、冲突提示、成功后重载、切换简报历史按钮；控制台无错误。
- CLI：行模式与 TUI `/timeline`、`/compare`、`/resume`；非交互拒绝。
- 冲突场景：切换后手改文件，再次切换被整体拒绝，叶子与 entry 不变。

- [ ] **Step 7: 提交**

```bash
git add pi-java-app/src/test/java/com/piagent/app/timeline/BranchResumeIntegrationTest.java \
  pi-java-app/src/test/java/com/piagent/app/timeline/BranchComparisonIntegrationTest.java \
  docs/ README.md
git commit -m "test(branch): cover round-trip branch resume and document milestone"
```

---

## 自检

**Spec 覆盖**

| Spec 章节 | 任务 |
|---|---|
| §4 分支移动规则 | M25-2 |
| §5 载荷字段 | M25-1、M25-2 |
| §6 树证据与保活 | M25-1、M25-2 |
| §7 应用服务 | M25-2 |
| §8 时间线与对比 | M25-4 |
| §9 HTTP API | M25-3 |
| §10 Web | M25-5 |
| §11 CLI | M25-4（编译）、M25-6（入口） |
| §12 边界 | M25-2 单测、M25-7 集成 |
| §13 里程碑 | 本计划任务一一对应 |
| §14 测试 | 各任务单测 + M25-7 |
| §15 风险 | M25-2 冲突/不可用处理、M25-7 文档 |
| §16 验收 | M25-7 全量验证 |
| §17 文档 | M25-7 |

**类型一致性检查**

- `BranchResumeResult` 字段：`dryRun, switched, available, restorable, unavailableReason, targetLeafId, files, restored, conflicts, briefEntryId, leftEntries, leftRuns, targetEntries, targetRuns, brief`。
- `SessionBranchComparison.branchEntryId` 与 `SessionComparisonResponse.branchEntryId` 一致。
- 时间线节点字段 `branchEntryId, brief, leftEntries, leftRuns` 在应用层、HTTP、Web、CLI 四处一致。
- `BranchEvidence` 与 `BranchSummaryPayload.details` 的 `leftEntries/leftRuns` 一致；旧 rewind 数据回退读 `discardedEntries/discardedRuns`。
