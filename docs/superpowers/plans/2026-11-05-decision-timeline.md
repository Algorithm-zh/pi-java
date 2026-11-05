# Decision Timeline & Arbitrary Checkpoint Rewind Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a read-only decision timeline and arbitrary checkpoint rewind so the user can see each run's intent, plan, approvals, verification and diffs, then return the workspace and conversation to any earlier checkpoint with a deterministic rewind brief.

**Architecture:** A read-only projection (`SessionTimelineService`) turns the current entry branch into RUN nodes anchored by `GIT_CHECKPOINT` and REWIND nodes anchored by rewind `BRANCH_SUMMARY` entries. A transactional use case (`SessionRewindService`) computes a dry-run restore plan through `WorkspaceGit.planRestore`, revalidates on execution through `WorkspaceGit.restore`, rewinds the session leaf to `checkpoint.baseLeafId`, and appends a structural `BRANCH_SUMMARY` brief. Checkpoints are kept alive by internal `refs/pi/checkpoints/...` refs.

**Tech Stack:** Java 21, Spring Boot 3.5.16, multi-module Maven, JUnit 5 + AssertJ + Mockito, git CLI, vanilla ES modules + CSS.

---

## File Map

| Action | Path | Responsibility |
|---|---|---|
| Create | `pi-java-domain/src/main/java/com/piagent/domain/git/model/GitRestorePlan.java` | Read-only restore plan |
| Modify | `pi-java-domain/src/main/java/com/piagent/domain/git/port/WorkspaceGit.java` | `planRestore`, `pinCheckpoint`, `clearCheckpoints` |
| Modify | `pi-java-infrastructure/src/main/java/com/piagent/infrastructure/git/CliGitWorkspace.java` | Implement new port methods |
| Create | `pi-java-infrastructure/src/main/java/com/piagent/infrastructure/git/GitCheckpointPins.java` | Internal ref pin/clear helper |
| Modify | `pi-java-infrastructure/src/main/java/com/piagent/infrastructure/git/DisabledWorkspaceGit.java` | Degrade new methods |
| Modify | `pi-java-infrastructure/src/main/java/com/piagent/infrastructure/config/GitSettings.java` | `pin-checkpoints` setting |
| Modify | `pi-java-app/src/main/resources/application.yml` | `pi.git.pin-checkpoints: true` |
| Modify | `pi-java-application/src/main/java/com/piagent/application/git/GitCheckpointService.java` | Pin checkpoint at run start |
| Modify | `pi-java-application/src/main/java/com/piagent/application/session/service/SessionSettingsApplicationService.java` | Clear refs on permanent delete |
| Create | `pi-java-application/src/main/java/com/piagent/application/timeline/BranchSummaryAppender.java` | Rewind brief append on idle sessions |
| Create | `pi-java-application/src/main/java/com/piagent/application/timeline/SessionTimeline.java` | Timeline read model |
| Create | `pi-java-application/src/main/java/com/piagent/application/timeline/SessionTimelineService.java` | Branch projection |
| Create | `pi-java-application/src/main/java/com/piagent/application/timeline/RewindBrief.java` | Brief text + details |
| Create | `pi-java-application/src/main/java/com/piagent/application/timeline/RewindBriefBuilder.java` | Deterministic brief |
| Create | `pi-java-application/src/main/java/com/piagent/application/timeline/SessionRewindResult.java` | Rewind result |
| Create | `pi-java-application/src/main/java/com/piagent/application/timeline/SessionRewindService.java` | Preview/execute |
| Create | `pi-java-trigger/src/main/java/com/piagent/trigger/http/session/SessionTimelineController.java` | HTTP endpoints |
| Create | `pi-java-trigger/src/main/java/com/piagent/trigger/http/session/SessionTimelineResponse.java` | Timeline DTO |
| Create | `pi-java-trigger/src/main/java/com/piagent/trigger/http/session/SessionRewindRequest.java` | Request DTO |
| Create | `pi-java-trigger/src/main/java/com/piagent/trigger/http/session/SessionRewindResponse.java` | Response DTO |
| Modify | `pi-java-trigger/src/main/java/com/piagent/trigger/http/ApiExceptionHandler.java` | `INVALID_BRANCH` -> 409 |
| Modify | `pi-java-trigger/src/main/java/com/piagent/trigger/http/session/SessionEntryResponse.java` | Expose branch summary `details` |
| Create | `pi-java-app/src/main/resources/static/js/timeline.js` | Timeline panel + preview |
| Create | `pi-java-app/src/main/resources/static/css/timeline.css` | Panel and preview styles |
| Modify | `pi-java-app/src/main/resources/static/index.html` | Topbar button, panel, preview dialog |
| Modify | `pi-java-app/src/main/resources/static/js/api.js` | Two API methods |
| Modify | `pi-java-app/src/main/resources/static/js/store.js` | DOM handles |
| Modify | `pi-java-app/src/main/resources/static/js/app.js` | Init timeline |
| Modify | `pi-java-app/src/main/resources/static/js/session-view.js` | Refresh/reset timeline |
| Modify | `pi-java-app/src/main/resources/static/js/run.js` | Refresh after run |
| Modify | `pi-java-app/src/main/resources/static/js/turn-view.js` | Rewind brief card |
| Modify | `docs/12-git.md`, `docs/adr/0005-git-checkpoints.md`, `docs/05-api-and-cli.md` | Documentation |
| Create | `docs/milestones/M22.md` | Milestone record |

---

## Task 1: Git restore plan and checkpoint pinning

**Files:**
- Create: `pi-java-domain/src/main/java/com/piagent/domain/git/model/GitRestorePlan.java`
- Modify: `pi-java-domain/src/main/java/com/piagent/domain/git/port/WorkspaceGit.java:15-47`
- Modify: `pi-java-infrastructure/src/main/java/com/piagent/infrastructure/git/CliGitWorkspace.java:103-162`
- Create: `pi-java-infrastructure/src/main/java/com/piagent/infrastructure/git/GitCheckpointPins.java`
- Modify: `pi-java-infrastructure/src/main/java/com/piagent/infrastructure/git/DisabledWorkspaceGit.java`
- Test: `pi-java-infrastructure/src/test/java/com/piagent/infrastructure/git/CliGitWorkspaceTest.java`

- [ ] **Step 1: Add the failing infrastructure tests**

Append these tests to `CliGitWorkspaceTest` (before the private helpers):

```java
    @Test
    void planRestoreListsFilesWithoutWriting() throws IOException {
        GitSnapshot before = committedRepo();
        write("a.txt", "one\nagent\n");
        write("new.txt", "fresh\n");
        GitSnapshot after = workspace.snapshot(repo).orElseThrow();

        var plan = workspace.planRestore(repo, before.treeSha(), after.treeSha());

        assertThat(plan.restorable()).isTrue();
        assertThat(plan.files()).containsExactlyInAnyOrder("a.txt", "new.txt");
        assertThat(plan.conflicts()).isEmpty();
        assertThat(Files.readString(repo.resolve("a.txt"))).isEqualTo("one\nagent\n");
        assertThat(Files.exists(repo.resolve("new.txt"))).isTrue();
    }

    @Test
    void planRestoreReportsConflictsWithoutWriting() throws IOException {
        GitSnapshot before = committedRepo();
        write("a.txt", "one\nagent\n");
        GitSnapshot after = workspace.snapshot(repo).orElseThrow();
        write("a.txt", "one\nuser\n");

        var plan = workspace.planRestore(repo, before.treeSha(), after.treeSha());

        assertThat(plan.restorable()).isFalse();
        assertThat(plan.conflicts()).extracting(conflict -> conflict.path())
                .containsExactly("a.txt");
        assertThat(Files.readString(repo.resolve("a.txt"))).isEqualTo("one\nuser\n");
    }

    @Test
    void restoreAppliesAnyTargetTreePair() throws IOException {
        GitSnapshot root = committedRepo();
        write("a.txt", "one\nagent\n");
        write("new.txt", "fresh\n");
        write("b.txt", "agent\n");
        GitSnapshot latest = workspace.snapshot(repo).orElseThrow();

        GitRestoreResult result = workspace.restore(repo, root.treeSha(), latest.treeSha());

        assertThat(result.complete()).isTrue();
        assertThat(result.restored()).containsExactlyInAnyOrder(
                "a.txt", "b.txt", "new.txt");
        assertThat(Files.readString(repo.resolve("a.txt"))).isEqualTo("one\ntwo\n");
        assertThat(Files.readString(repo.resolve("b.txt"))).isEqualTo("keep\n");
        assertThat(Files.exists(repo.resolve("new.txt"))).isFalse();
        assertThat(status()).isBlank();
    }

    @Test
    void pinCheckpointSurvivesGcAndClearRemovesRefs() throws IOException {
        GitSnapshot before = committedRepo();
        workspace.pinCheckpoint(repo, "session-1", "run-1", before.treeSha());

        git("gc", "--prune=now", "--quiet");
        assertThat(git("cat-file", "-t", before.treeSha())).contains("tree");
        assertThat(git("for-each-ref", "--format=%(refname)",
                "refs/pi/checkpoints/session-1/")).contains("run-1");

        workspace.clearCheckpoints(repo, "session-1");
        assertThat(git("for-each-ref", "--format=%(refname)",
                "refs/pi/checkpoints/session-1/")).isBlank();
    }

    @Test
    void pinCheckpointCanBeDisabled() throws IOException {
        CliGitWorkspace pinnedOff = new CliGitWorkspace(new GitSettings(true, 10, 1_000, false));
        GitSnapshot before = committedRepo();

        pinnedOff.pinCheckpoint(repo, "session-1", "run-1", before.treeSha());

        assertThat(git("for-each-ref", "--format=%(refname)",
                "refs/pi/checkpoints/session-1/")).isBlank();
    }
```

- [ ] **Step 2: Run the tests and confirm failure**

Run:

```bash
cd D:/code/pi-java
JAVA_HOME="C:/Users/R9000P/.jdks/ms-21.0.10" mvn -o -pl pi-java-infrastructure -am test \
  -Dtest=CliGitWorkspaceTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: compilation fails because `planRestore`, `pinCheckpoint`, `clearCheckpoints` and the four-argument `GitSettings` do not exist.

- [ ] **Step 3: Create `GitRestorePlan`**

```java
package com.piagent.domain.git.model;

import java.util.List;

/**
 * 一次“把工作区还原到目标树”的只读计划。
 *
 * <p>只描述会改动哪些路径以及哪些路径冲突，不写工作区；调用方可以先展示预览，
 * 执行时再调用 {@code restore} 重新校验。</p>
 *
 * @param targetTreeSha 目标树，即 run 开始前的工作区状态
 * @param latestTreeSha 当前分支上最后已知的工作区树
 * @param files 目标树与最新树之间有变化的路径
 * @param conflicts 因工作区又发生变化而无法安全还原的路径
 */
public record GitRestorePlan(String targetTreeSha, String latestTreeSha,
        List<String> files, List<GitConflict> conflicts) {

    /**
     * 是否可以在不覆盖用户改动的前提下执行。
     *
     * @return true 表示没有冲突
     */
    public boolean restorable() {
        return conflicts.isEmpty();
    }
}
```

- [ ] **Step 4: Extend the `WorkspaceGit` port**

Add these methods after `restore`:

```java
    /**
     * 计算把工作区还原到起点树的只读计划，不写任何文件。
     *
     * <p>默认实现表示该 Git 适配器不支持预览，保持既有测试替身可编译；
     * 生产实现必须覆盖。</p>
     *
     * @param workspace 工作目录
     * @param baseTreeSha 目标树
     * @param treeSha 当前分支上最后已知的树
     * @return 还原计划
     */
    default GitRestorePlan planRestore(Path workspace, String baseTreeSha, String treeSha) {
        throw new UnsupportedOperationException("当前 Git 实现不支持还原计划");
    }

    /**
     * 为检查点创建内部保活 ref，避免树对象被 {@code git gc} 清理。
     *
     * <p>默认不保活，适配器可覆盖。</p>
     *
     * @param workspace 工作目录
     * @param namespace 命名空间，通常为会话 ID
     * @param checkpointId 检查点标识，通常为 run ID
     * @param treeSha 需要保活的树
     */
    default void pinCheckpoint(Path workspace, String namespace, String checkpointId,
            String treeSha) {
    }

    /**
     * 删除某个命名空间下的全部保活 ref。
     *
     * @param workspace 工作目录
     * @param namespace 命名空间
     */
    default void clearCheckpoints(Path workspace, String namespace) {
    }
```

Add the import `com.piagent.domain.git.model.GitRestorePlan`.

- [ ] **Step 5: Create `GitCheckpointPins`**

```java
package com.piagent.infrastructure.git;

import java.nio.file.Path;
import java.util.Map;

/**
 * 检查点保活 ref 的 git CLI 实现。
 *
 * <p>为树对象创建一个只存在于 {@code refs/pi/checkpoints/} 命名空间的内部 commit，
 * 不移动 HEAD、不创建用户可见分支，也不写用户索引。所有失败都只记日志。</p>
 */
final class GitCheckpointPins {

    private static final System.Logger LOG = System.getLogger(GitCheckpointPins.class.getName());

    private static final Map<String, String> IDENTITY = Map.of(
            "GIT_AUTHOR_NAME", "pi-java",
            "GIT_AUTHOR_EMAIL", "pi-java@localhost",
            "GIT_COMMITTER_NAME", "pi-java",
            "GIT_COMMITTER_EMAIL", "pi-java@localhost");

    private static final String PREFIX = "refs/pi/checkpoints/";

    private final GitCommandRunner runner;

    GitCheckpointPins(GitCommandRunner runner) {
        this.runner = runner;
    }

    void pin(Path root, String namespace, String checkpointId, String treeSha) {
        GitCommandRunner.Result commit = runner.run(root, IDENTITY,
                "commit-tree", treeSha, "-m", "pi checkpoint " + checkpointId);
        if (!commit.success()) {
            LOG.log(System.Logger.Level.WARNING, "创建检查点 commit 失败: {0}", commit.errorLine());
            return;
        }
        GitCommandRunner.Result ref = runner.run(root, Map.of(), "update-ref",
                PREFIX + namespace + "/" + checkpointId, commit.out());
        if (!ref.success()) {
            LOG.log(System.Logger.Level.WARNING, "写入检查点 ref 失败: {0}", ref.errorLine());
        }
    }

    void clear(Path root, String namespace) {
        String prefix = PREFIX + namespace + "/";
        GitCommandRunner.Result refs = runner.run(root, Map.of(),
                "for-each-ref", "--format=%(refname)", prefix);
        if (!refs.success()) {
            LOG.log(System.Logger.Level.WARNING, "列出检查点 ref 失败: {0}", refs.errorLine());
            return;
        }
        for (String ref : refs.out().split("\\R")) {
            if (ref.isBlank()) {
                continue;
            }
            GitCommandRunner.Result deleted = runner.run(root, Map.of(),
                    "update-ref", "-d", ref.strip());
            if (!deleted.success()) {
                LOG.log(System.Logger.Level.WARNING, "删除检查点 ref 失败: {0}",
                        deleted.errorLine());
            }
        }
    }
}
```

- [ ] **Step 6: Implement the port in `CliGitWorkspace`**

Replace `restore` (lines 103-122) with:

```java
    @Override
    public GitRestoreResult restore(Path workspace, String baseTreeSha, String treeSha) {
        GitRestorePlan plan = planRestore(workspace, baseTreeSha, treeSha);
        if (!plan.restorable()) {
            return new GitRestoreResult(List.of(), plan.conflicts());
        }
        if (plan.files().isEmpty()) {
            return new GitRestoreResult(List.of(), List.of());
        }
        Path root = repositoryRoot(workspace);
        if (root == null) {
            throw new DomainException(DomainErrorCode.GIT_UNAVAILABLE, "工作目录不是 Git 仓库");
        }
        return new GitRestoreResult(apply(root, baseTreeSha, plan.files()), List.of());
    }

    @Override
    public GitRestorePlan planRestore(Path workspace, String baseTreeSha, String treeSha) {
        Path root = repositoryRoot(workspace);
        if (root == null) {
            throw new DomainException(DomainErrorCode.GIT_UNAVAILABLE, "工作目录不是 Git 仓库");
        }
        GitSnapshot current = snapshot(root)
                .orElseThrow(() -> new DomainException(DomainErrorCode.GIT_UNAVAILABLE,
                        "无法读取当前工作区快照"));
        List<FileChange> changed = changedPaths(root, baseTreeSha, treeSha);
        List<String> files = changed.stream().map(FileChange::path).toList();
        List<GitConflict> conflicts = files.isEmpty() ? List.of()
                : conflicts(root, changed, current.treeSha(), treeSha);
        return new GitRestorePlan(baseTreeSha, treeSha, files, conflicts);
    }

    @Override
    public void pinCheckpoint(Path workspace, String namespace, String checkpointId,
            String treeSha) {
        if (!pinCheckpoints) {
            return;
        }
        Path root = repositoryRoot(workspace);
        if (root != null) {
            pins.pin(root, namespace, checkpointId, treeSha);
        }
    }

    @Override
    public void clearCheckpoints(Path workspace, String namespace) {
        Path root = repositoryRoot(workspace);
        if (root != null) {
            pins.clear(root, namespace);
        }
    }
```

Add fields and constructor wiring:

```java
    private final GitCommandRunner runner;
    private final int maxDiffChars;
    private final boolean pinCheckpoints;
    private final GitCheckpointPins pins;

    public CliGitWorkspace(GitSettings settings) {
        this.runner = new GitCommandRunner(Duration.ofSeconds(settings.timeoutSeconds()));
        this.maxDiffChars = settings.maxDiffChars();
        this.pinCheckpoints = settings.isPinCheckpoints();
        this.pins = new GitCheckpointPins(runner);
    }
```

Change `apply` to accept paths:

```java
    private List<String> apply(Path root, String baseTreeSha, List<String> paths) {
        Set<String> restored = new LinkedHashSet<>();
        List<String> toCheckout = new ArrayList<>();
        for (String path : paths) {
            String baseBlob = blobAt(root, baseTreeSha, path);
            if (baseBlob == null) {
                deleteWorktreeFile(root, path);
            } else {
                toCheckout.add(path);
            }
            restored.add(path);
        }
        if (!toCheckout.isEmpty()) {
            checkout(root, baseTreeSha, toCheckout);
        }
        return new ArrayList<>(restored);
    }
```

Add the import `com.piagent.domain.git.model.GitRestorePlan`.

- [ ] **Step 7: Degrade in `DisabledWorkspaceGit`**

Add:

```java
    @Override
    public GitRestorePlan planRestore(Path workspace, String baseTreeSha, String treeSha) {
        throw disabled();
    }

    @Override
    public void pinCheckpoint(Path workspace, String namespace, String checkpointId,
            String treeSha) {
        // 显式关闭 Git 时没有检查点，也就不需要保活。
    }

    @Override
    public void clearCheckpoints(Path workspace, String namespace) {
        // 没有保活 ref 需要清理。
    }
```

Add the import `com.piagent.domain.git.model.GitRestorePlan`.

- [ ] **Step 8: Add the `GitSettings` flag**

Replace the record header and compact constructor with:

```java
public record GitSettings(Boolean enabled, Integer timeoutSeconds, Integer maxDiffChars,
        Boolean pinCheckpoints) {

    private static final int DEFAULT_TIMEOUT_SECONDS = 10;
    private static final int DEFAULT_MAX_DIFF_CHARS = 60_000;

    /**
     * 三参数便捷构造，保留既有测试与调用点。
     *
     * @param enabled 是否启用
     * @param timeoutSeconds 超时秒数
     * @param maxDiffChars diff 上限
     */
    public GitSettings(Boolean enabled, Integer timeoutSeconds, Integer maxDiffChars) {
        this(enabled, timeoutSeconds, maxDiffChars, null);
    }

    public GitSettings {
        enabled = enabled == null ? Boolean.TRUE : enabled;
        timeoutSeconds = timeoutSeconds == null ? DEFAULT_TIMEOUT_SECONDS : timeoutSeconds;
        maxDiffChars = maxDiffChars == null ? DEFAULT_MAX_DIFF_CHARS : maxDiffChars;
        pinCheckpoints = pinCheckpoints == null ? Boolean.TRUE : pinCheckpoints;
        if (timeoutSeconds < 1) {
            throw new IllegalArgumentException("pi.git.timeout-seconds 必须大于 0");
        }
        if (maxDiffChars < 1_000) {
            throw new IllegalArgumentException("pi.git.max-diff-chars 不能小于 1000");
        }
    }

    public static GitSettings defaults() {
        return new GitSettings(true, DEFAULT_TIMEOUT_SECONDS, DEFAULT_MAX_DIFF_CHARS);
    }

    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    /**
     * 是否为检查点创建内部保活 ref。
     *
     * @return true 表示保活
     */
    public boolean isPinCheckpoints() {
        return Boolean.TRUE.equals(pinCheckpoints);
    }
}
```

Update the record Javadoc with:

```java
 * @param pinCheckpoints 是否为检查点创建内部保活 ref
```

- [ ] **Step 9: Run the tests and confirm they pass**

Run:

```bash
cd D:/code/pi-java
JAVA_HOME="C:/Users/R9000P/.jdks/ms-21.0.10" mvn -o -pl pi-java-infrastructure -am test \
  -Dtest=CliGitWorkspaceTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `BUILD SUCCESS`, all `CliGitWorkspaceTest` tests pass.

- [ ] **Step 10: Commit**

```bash
cd D:/code/pi-java
git add pi-java-domain/src/main/java/com/piagent/domain/git/model/GitRestorePlan.java \
  pi-java-domain/src/main/java/com/piagent/domain/git/port/WorkspaceGit.java \
  pi-java-infrastructure/src/main/java/com/piagent/infrastructure/git/CliGitWorkspace.java \
  pi-java-infrastructure/src/main/java/com/piagent/infrastructure/git/GitCheckpointPins.java \
  pi-java-infrastructure/src/main/java/com/piagent/infrastructure/git/DisabledWorkspaceGit.java \
  pi-java-infrastructure/src/main/java/com/piagent/infrastructure/config/GitSettings.java \
  pi-java-infrastructure/src/test/java/com/piagent/infrastructure/git/CliGitWorkspaceTest.java
git commit -m "feat(m22): add restore plans and checkpoint pinning"
```

---

## Task 2: Pin checkpoints on run start and clear on delete

**Files:**
- Modify: `pi-java-app/src/main/resources/application.yml:84-88`
- Modify: `pi-java-application/src/main/java/com/piagent/application/git/GitCheckpointService.java:68-82`
- Modify: `pi-java-application/src/main/java/com/piagent/application/session/service/SessionSettingsApplicationService.java:27-45, 99-106`
- Test: `pi-java-application/src/test/java/com/piagent/application/git/GitCheckpointServiceTest.java`
- Test: `pi-java-application/src/test/java/com/piagent/application/session/service/SessionSettingsApplicationServiceTest.java`

- [ ] **Step 1: Add the failing service tests**

In `GitCheckpointServiceTest`, add a test and update `FakeGit`:

```java
    @Test
    void pinsCheckpointWhenRunStarts() {
        SessionId sessionId = createSession().identity().id();
        appender.appendUserAndStart(sessionId, "改一下文件");
        git.snapshots.add(new GitSnapshot("tree-0", "head-0", "main"));

        checkpoints.beginRun(sessionId, WORKSPACE, RunId.newId(ids), null);

        assertThat(git.pinnedNamespace).isEqualTo(sessionId.toString());
        assertThat(git.pinnedTree).isEqualTo("tree-0");
    }
```

Add to `FakeGit`:

```java
        private GitRestorePlan nextPlan = new GitRestorePlan("tree-0", "tree-0",
                List.of(), List.of());
        private String pinnedNamespace;
        private String pinnedTree;

        @Override
        public GitRestorePlan planRestore(Path workspace, String baseTreeSha, String treeSha) {
            return nextPlan;
        }

        @Override
        public void pinCheckpoint(Path workspace, String namespace, String checkpointId,
                String treeSha) {
            pinnedNamespace = namespace;
            pinnedTree = treeSha;
        }

        @Override
        public void clearCheckpoints(Path workspace, String namespace) {
            // 测试替身无需清理。
        }
```

Add the import `com.piagent.domain.git.model.GitRestorePlan`.

In `SessionSettingsApplicationServiceTest`, add the mock and update the three constructor sites to use it:

```java
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.piagent.domain.git.port.WorkspaceGit;
import java.nio.file.Path;
```

```java
    private WorkspaceGit git;

    @BeforeEach
    void setUp() {
        sessions = new InMemorySessionRepository();
        models = new InMemoryModelProfileRepository();
        git = mock(WorkspaceGit.class);
        service = new SessionSettingsApplicationService(sessions, models,
                new ModelSelectionPolicy(), git);
```

Replace the two later reassignments (running-session tests) with:

```java
        service = new SessionSettingsApplicationService(sessions, models,
                new ModelSelectionPolicy(), git);
```

Add to `deletesIdleSession`:

```java
        verify(git).clearCheckpoints(any(Path.class), eq(SESSION_ID.toString()));
```

- [ ] **Step 2: Run the tests and confirm failure**

```bash
cd D:/code/pi-java
JAVA_HOME="C:/Users/R9000P/.jdks/ms-21.0.10" mvn -o -pl pi-java-application -am test \
  -Dtest=GitCheckpointServiceTest,SessionSettingsApplicationServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: compilation fails because the constructors do not accept the new argument and the test fakes lack the new methods.

- [ ] **Step 3: Add the config key**

In `pi-java-app/src/main/resources/application.yml`, under `pi.git`:

```yaml
  git:
    # 运行前后快照与逐轮 diff；工作区不是 Git 仓库时自动跳过。
    enabled: true
    timeout-seconds: 10
    max-diff-chars: 60000
    # 为检查点创建内部保活 ref，避免 git gc 清理旧树；会话删除时清理。
    pin-checkpoints: true
```

- [ ] **Step 4: Pin in `GitCheckpointService.beginRun`**

After the existing `appendMetadata` try/catch, add:

```java
        try {
            git.pinCheckpoint(workspace, sessionId.toString(), runId.toString(), value.treeSha());
        } catch (RuntimeException error) {
            LOG.log(System.Logger.Level.WARNING, "保活 Git 检查点失败: " + sessionId, error);
        }
```

- [ ] **Step 5: Clear refs on permanent delete**

In `SessionSettingsApplicationService`, add the logger and field:

```java
    private static final System.Logger LOG =
            System.getLogger(SessionSettingsApplicationService.class.getName());

    private final WorkspaceGit git;
```

Change the constructor to four parameters and assign `this.git = git;`. Update its Javadoc with:

```java
     * @param git 工作区 Git 端口，用于删除会话时清理检查点引用
```

Change `delete` to:

```java
    @Transactional
    public void delete(SessionId sessionId) {
        ChatSession current = require(sessionId);
        Guard.require(current.status() == SessionStatus.IDLE
                        || current.status() == SessionStatus.ARCHIVED,
                DomainErrorCode.INVALID_STATE_TRANSITION, "会话正在运行，请先停止再删除");
        sessions.delete(sessionId);
        try {
            git.clearCheckpoints(current.config().cwd(), sessionId.toString());
        } catch (RuntimeException error) {
            LOG.log(System.Logger.Level.WARNING, "清理检查点引用失败: " + sessionId, error);
        }
    }
```

Add the import `com.piagent.domain.git.port.WorkspaceGit`.

- [ ] **Step 6: Run the tests and confirm they pass**

```bash
cd D:/code/pi-java
JAVA_HOME="C:/Users/R9000P/.jdks/ms-21.0.10" mvn -o -pl pi-java-application -am test \
  -Dtest=GitCheckpointServiceTest,SessionSettingsApplicationServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 7: Commit**

```bash
cd D:/code/pi-java
git add pi-java-app/src/main/resources/application.yml \
  pi-java-application/src/main/java/com/piagent/application/git/GitCheckpointService.java \
  pi-java-application/src/main/java/com/piagent/application/session/service/SessionSettingsApplicationService.java \
  pi-java-application/src/test/java/com/piagent/application/git/GitCheckpointServiceTest.java \
  pi-java-application/src/test/java/com/piagent/application/session/service/SessionSettingsApplicationServiceTest.java
git commit -m "feat(m22): pin checkpoints and clean refs on session delete"
```

---

## Task 3: Create the rewind brief appender

**Files:**
- Create: `pi-java-application/src/main/java/com/piagent/application/timeline/BranchSummaryAppender.java`
- Test: `pi-java-application/src/test/java/com/piagent/application/timeline/BranchSummaryAppenderTest.java`

`SessionEntryAppender` is 298 lines and Checkstyle caps files at 300, so the brief appender lives in its own focused class. It mirrors the same entry shape (seq = existing count + 1, parent = current leaf) but only accepts IDLE sessions.

- [ ] **Step 1: Write the failing test**

Create `BranchSummaryAppenderTest`:

```java
package com.piagent.application.timeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.application.agent.service.SessionEntryAppender;
import com.piagent.application.testsupport.InMemorySessionEntryStore;
import com.piagent.application.testsupport.InMemorySessionRepository;
import com.piagent.application.testsupport.SequentialIdGenerator;
import com.piagent.application.testsupport.SessionEntryFactory;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.EntryType;
import com.piagent.domain.session.model.SandboxSnapshot;
import com.piagent.domain.session.model.SessionConfig;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.ThinkingLevel;
import com.piagent.domain.session.model.TokenUsage;
import com.piagent.domain.session.model.payload.BranchSummaryPayload;
import com.piagent.domain.session.model.payload.RunSummaryPayload;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BranchSummaryAppenderTest {

    private static final Instant NOW = Instant.parse("2026-11-05T00:00:00Z");
    private static final Path WORKSPACE = Path.of("target/branch-summary-unit").toAbsolutePath();

    private final InMemorySessionRepository sessions = new InMemorySessionRepository();
    private final InMemorySessionEntryStore entries = new InMemorySessionEntryStore();
    private final SequentialIdGenerator ids = new SequentialIdGenerator();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final SessionEntryAppender runs = new SessionEntryAppender(sessions, entries, ids, clock);
    private final BranchSummaryAppender summaries = new BranchSummaryAppender(sessions, entries, ids,
            clock);

    @Test
    void appendsBriefAsRootWhenBranchIsEmpty() {
        SessionId sessionId = createSession().identity().id();

        SessionEntry brief = summaries.append(sessionId,
                new BranchSummaryPayload(SessionEntryFactory.entryId(999), "回溯简报",
                        Map.of("kind", "rewind")));

        assertThat(brief.seq()).isEqualTo(1);
        assertThat(brief.parentId()).isNull();
        assertThat(brief.type()).isEqualTo(EntryType.BRANCH_SUMMARY);
        assertThat(sessions.findById(sessionId).orElseThrow().leafEntryId())
                .isEqualTo(brief.id());
    }

    @Test
    void appendsBriefAfterCurrentLeaf() {
        SessionId sessionId = createSession().identity().id();
        SessionEntry user = runs.appendUserAndStart(sessionId, "第一轮");
        SessionEntry summary = runs.finishRun(sessionId, new RunSummaryPayload("run-1", "STOP",
                TokenUsage.zero(), 1, 0, 800, null));

        SessionEntry brief = summaries.append(sessionId,
                new BranchSummaryPayload(user.id(), "回溯简报", Map.of("kind", "rewind")));

        assertThat(brief.parentId()).isEqualTo(summary.id());
        assertThat(brief.seq()).isEqualTo(3);
        assertThat(sessions.findById(sessionId).orElseThrow().leafEntryId())
                .isEqualTo(brief.id());
    }

    @Test
    void rejectsBriefWhileRunning() {
        SessionId sessionId = createSession().identity().id();
        SessionEntry user = runs.appendUserAndStart(sessionId, "还在跑");

        assertThatThrownBy(() -> summaries.append(sessionId,
                new BranchSummaryPayload(user.id(), "回溯简报", Map.of())))
                .isInstanceOfSatisfying(DomainException.class, error ->
                        assertThat(error.code())
                                .isEqualTo(DomainErrorCode.INVALID_STATE_TRANSITION));
    }

    private ChatSession createSession() {
        ChatSession session = ChatSession.createRoot(SessionId.newId(ids), "摘要追加器测试",
                new SessionConfig(WORKSPACE, "fake-demo", ThinkingLevel.OFF,
                        SandboxSnapshot.forWorkspace(WORKSPACE)));
        sessions.insert(session);
        return session;
    }
}
```

- [ ] **Step 2: Run the test and confirm failure**

```bash
cd D:/code/pi-java
JAVA_HOME="C:/Users/R9000P/.jdks/ms-21.0.10" mvn -o -pl pi-java-application -am test \
  -Dtest=BranchSummaryAppenderTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: compilation fails because `BranchSummaryAppender` does not exist.

- [ ] **Step 3: Implement `BranchSummaryAppender`**

Create the class:

```java
package com.piagent.application.timeline;

import com.piagent.domain.context.EntryTokenCounter;
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
import com.piagent.domain.shared.Guard;
import com.piagent.domain.shared.IdGenerator;
import com.piagent.domain.shared.Ulid;
import java.time.Clock;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 在空闲会话上追加回溯简报的追加器。
 *
 * <p>回退后旧 entry 不再位于当前分支；简报成为新分支的起点，回退第一轮时它是根 entry。
 * 单独成类是因为 {@code SessionEntryAppender} 已接近 Checkstyle 的文件行数上限。</p>
 */
@Service
public class BranchSummaryAppender {

    private static final EntryTokenCounter TOKEN_COUNTER = new EntryTokenCounter();

    private final SessionRepository repository;
    private final SessionEntryStore entryStore;
    private final IdGenerator idGenerator;
    private final Clock clock;

    /**
     * 创建追加器。
     *
     * @param repository 会话仓储
     * @param entryStore entry 存储
     * @param idGenerator 标识生成器
     * @param clock 时钟
     */
    public BranchSummaryAppender(SessionRepository repository, SessionEntryStore entryStore,
            IdGenerator idGenerator, Clock clock) {
        this.repository = repository;
        this.entryStore = entryStore;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    /**
     * 追加一条分支摘要并把叶子移到该 entry。
     *
     * @param sessionId 会话标识
     * @param payload 分支摘要载荷
     * @return 新 entry
     */
    @Transactional
    public SessionEntry append(SessionId sessionId, BranchSummaryPayload payload) {
        Objects.requireNonNull(payload, "payload 不能为空");
        ChatSession session = repository.findById(sessionId)
                .orElseThrow(() -> new DomainException(DomainErrorCode.SESSION_NOT_FOUND,
                        "会话不存在: " + sessionId));
        Guard.require(session.status() == SessionStatus.IDLE,
                DomainErrorCode.INVALID_STATE_TRANSITION,
                "只有空闲会话可以追加回溯简报，当前状态: " + session.status());
        SessionEntry entry = build(session, payload);
        entryStore.append(entry);
        repository.update(session.moveLeaf(entry.id()));
        return entry;
    }

    private SessionEntry build(ChatSession session, BranchSummaryPayload payload) {
        long seq = entryStore.countBySession(session.identity().id()) + 1;
        int tokens = Math.max(1, TOKEN_COUNTER.countText(payload.summary()));
        EntryId id = new EntryId(Ulid.parse(idGenerator.newId()));
        return new SessionEntry(id, session.identity().id(), seq, session.leafEntryId(),
                payload.entryType(), SessionEntry.CURRENT_PAYLOAD_VERSION, payload, tokens,
                clock.instant());
    }
}
```

- [ ] **Step 4: Run the test and confirm it passes**

```bash
cd D:/code/pi-java
JAVA_HOME="C:/Users/R9000P/.jdks/ms-21.0.10" mvn -o -pl pi-java-application -am test \
  -Dtest=BranchSummaryAppenderTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Check the new class against Checkstyle**

```bash
cd D:/code/pi-java
JAVA_HOME="C:/Users/R9000P/.jdks/ms-21.0.10" mvn -o -q -pl pi-java-application checkstyle:check
```

Expected: no violations.

- [ ] **Step 6: Commit**

```bash
cd D:/code/pi-java
git add pi-java-application/src/main/java/com/piagent/application/timeline/BranchSummaryAppender.java \
  pi-java-application/src/test/java/com/piagent/application/timeline/BranchSummaryAppenderTest.java
git commit -m "feat(m22): append rewind briefs to idle sessions"
```

---

## Task 4: Project the decision timeline

**Files:**
- Create: `pi-java-application/src/main/java/com/piagent/application/timeline/SessionTimeline.java`
- Create: `pi-java-application/src/main/java/com/piagent/application/timeline/SessionTimelineService.java`
- Test: `pi-java-application/src/test/java/com/piagent/application/timeline/SessionTimelineServiceTest.java`

- [ ] **Step 1: Write the failing tests**

```java
package com.piagent.application.timeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.piagent.application.testsupport.InMemorySessionEntryStore;
import com.piagent.application.testsupport.InMemorySessionRepository;
import com.piagent.application.testsupport.SequentialIdGenerator;
import com.piagent.application.testsupport.SessionEntryFactory;
import com.piagent.domain.git.model.FileChange;
import com.piagent.domain.git.model.GitChangeKind;
import com.piagent.domain.plan.model.PlanItem;
import com.piagent.domain.plan.model.PlanItemStatus;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.SandboxSnapshot;
import com.piagent.domain.session.model.SessionConfig;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.SessionStatus;
import com.piagent.domain.session.model.ThinkingLevel;
import com.piagent.domain.session.model.TokenUsage;
import com.piagent.domain.session.model.payload.ApprovalPayload;
import com.piagent.domain.session.model.payload.BranchSummaryPayload;
import com.piagent.domain.session.model.payload.EntryPayload;
import com.piagent.domain.session.model.payload.GitChangeSetPayload;
import com.piagent.domain.session.model.payload.GitCheckpointPayload;
import com.piagent.domain.session.model.payload.MessagePayload;
import com.piagent.domain.session.model.payload.PlanPayload;
import com.piagent.domain.session.model.payload.RunSummaryPayload;
import com.piagent.domain.session.model.payload.VerificationPayload;
import com.piagent.domain.session.model.message.UserMessage;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SessionTimelineServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");
    private static final Path WORKSPACE = Path.of("target/timeline-unit").toAbsolutePath();

    private final InMemorySessionRepository sessions = new InMemorySessionRepository();
    private final InMemorySessionEntryStore entries = new InMemorySessionEntryStore();
    private final SequentialIdGenerator ids = new SequentialIdGenerator();
    private final SessionTimelineService service = new SessionTimelineService(sessions, entries);

    private SessionId sessionId;
    private EntryId leaf;
    private long seq;

    @BeforeEach
    void setUp() {
        sessionId = SessionId.newId(ids);
        sessions.insert(ChatSession.createRoot(sessionId, "时间线测试",
                new SessionConfig(WORKSPACE, "fake-demo", ThinkingLevel.OFF,
                        SandboxSnapshot.forWorkspace(WORKSPACE))));
    }

    @Test
    void projectsCheckpointNodesWithEvidence() {
        EntryId run1 = append(new MessagePayload(new UserMessage(
                List.of(new com.piagent.domain.session.model.message.TextBlock("修复登录")), NOW)));
        EntryId checkpoint1 = append(new GitCheckpointPayload("run-1", null, "tree-0",
                "head-0", "main"));
        append(new PlanPayload("run-1", List.of(
                new PlanItem("改登录", PlanItemStatus.DONE),
                new PlanItem("补测试", PlanItemStatus.PENDING))));
        append(new ApprovalPayload("a1", "c1", "write", "DENIED", "先别改", "user"));
        append(new VerificationPayload("run-1", "mvn test", false, 1, false, false, 120, "fail"));
        append(new GitChangeSetPayload("run-1", 1, run1, "tree-0", "tree-1",
                List.of(new FileChange("A.java", GitChangeKind.MODIFIED, 10, 2, false)),
                "diff", false, "fix: login"));
        append(new RunSummaryPayload("run-1", "COMPLETED", TokenUsage.zero(), 1, 2, 900, null));
        EntryId run2 = append(new MessagePayload(new UserMessage(
                List.of(new com.piagent.domain.session.model.message.TextBlock("补测试")), NOW)));
        append(new GitCheckpointPayload("run-2", leaf, "tree-1", "head-0", "main"));
        append(new VerificationPayload("run-2", "mvn test", true, 0, false, false, 80, "ok"));
        append(new RunSummaryPayload("run-2", "COMPLETED", TokenUsage.zero(), 1, 0, 300, null));
        moveLeaf();

        SessionTimeline timeline = service.timeline(sessionId);

        assertThat(timeline.available()).isTrue();
        assertThat(timeline.nodes()).hasSize(2);
        SessionTimeline.Node first = timeline.nodes().getFirst();
        assertThat(first.checkpointEntryId()).isEqualTo(checkpoint1);
        assertThat(first.run().intent()).isEqualTo("修复登录");
        assertThat(first.run().stats().plan().total()).isEqualTo(2);
        assertThat(first.run().stats().plan().done()).isEqualTo(1);
        assertThat(first.run().stats().approvals().denied()).isEqualTo(1);
        assertThat(first.run().stats().verification().failed()).isEqualTo(1);
        assertThat(first.run().stats().changes().files()).isEqualTo(1);
        assertThat(first.run().stats().changes().additions()).isEqualTo(10);
        assertThat(first.current()).isFalse();
        assertThat(timeline.nodes().getLast().current()).isTrue();
        assertThat(timeline.currentCheckpointEntryId())
                .isEqualTo(timeline.nodes().getLast().checkpointEntryId());
        assertThat(run2).isNotNull();
    }

    @Test
    void insertsRewindNodeAtItsPositionAndClearsCurrentCheckpoint() {
        append(new MessagePayload(new UserMessage(
                List.of(new com.piagent.domain.session.model.message.TextBlock("第一次")), NOW)));
        append(new GitCheckpointPayload("run-1", null, "tree-0", "head-0", "main"));
        append(new RunSummaryPayload("run-1", "COMPLETED", TokenUsage.zero(), 1, 0, 100, null));
        EntryId oldLeaf = leaf;
        append(new BranchSummaryPayload(oldLeaf, "从 10:00 的决策点回退：丢弃 1 轮对话。",
                Map.of("kind", "rewind", "checkpointEntryId", oldLeaf.toString(),
                        "runId", "run-1", "discardedEntries", 3, "discardedRuns", 1)));
        moveLeaf();

        SessionTimeline timeline = service.timeline(sessionId);

        assertThat(timeline.nodes()).hasSize(2);
        assertThat(timeline.nodes().getFirst().current()).isFalse();
        assertThat(timeline.nodes().getLast().kind())
                .isEqualTo(SessionTimeline.NodeKind.REWIND);
        assertThat(timeline.nodes().getLast().current()).isTrue();
        assertThat(timeline.nodes().getLast().rewind().discardedEntries()).isEqualTo(3);
        assertThat(timeline.nodes().getLast().rewind().discardedRuns()).isEqualTo(1);
        assertThat(timeline.currentCheckpointEntryId()).isNull();
    }

    @Test
    void fallsBackToRunNodesWithoutCheckpoints() {
        append(new MessagePayload(new UserMessage(
                List.of(new com.piagent.domain.session.model.message.TextBlock("无 Git 会话")), NOW)));
        append(new RunSummaryPayload("run-1", "COMPLETED", TokenUsage.zero(), 1, 0, 100, null));
        moveLeaf();

        SessionTimeline timeline = service.timeline(sessionId);

        assertThat(timeline.available()).isFalse();
        assertThat(timeline.nodes()).singleElement().satisfies(node -> {
            assertThat(node.kind()).isEqualTo(SessionTimeline.NodeKind.RUN);
            assertThat(node.checkpointEntryId()).isNull();
            assertThat(node.rewindable()).isFalse();
            assertThat(node.run().intent()).isEqualTo("无 Git 会话");
        });
    }

    @Test
    void returnsEmptyTimelineForEmptySession() {
        assertThat(service.timeline(sessionId).nodes()).isEmpty();
        assertThat(service.timeline(sessionId).available()).isFalse();
        assertThat(service.timeline(sessionId).currentCheckpointEntryId()).isNull();
    }

    private EntryId append(EntryPayload payload) {
        seq++;
        EntryId id = SessionEntryFactory.entryId(500 + (int) seq);
        SessionEntry entry = new SessionEntry(id, sessionId, seq, leaf, payload.entryType(),
                SessionEntry.CURRENT_PAYLOAD_VERSION, payload, 0, NOW.plusMillis(seq));
        entries.append(entry);
        leaf = id;
        return id;
    }

    private void moveLeaf() {
        ChatSession session = sessions.findById(sessionId).orElseThrow();
        sessions.update(session.moveLeaf(leaf));
    }
}
```

- [ ] **Step 2: Run the tests and confirm failure**

```bash
cd D:/code/pi-java
JAVA_HOME="C:/Users/R9000P/.jdks/ms-21.0.10" mvn -o -pl pi-java-application -am test \
  -Dtest=SessionTimelineServiceTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: compilation fails because `SessionTimeline` and `SessionTimelineService` do not exist.

- [ ] **Step 3: Create the read model**

```java
package com.piagent.application.timeline;

import com.piagent.domain.plan.model.PlanItem;
import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.payload.ApprovalPayload;
import com.piagent.domain.session.model.payload.GitChangeSetPayload;
import com.piagent.domain.session.model.payload.VerificationPayload;
import java.time.Instant;
import java.util.List;

/**
 * 当前分支的决策时间线。
 *
 * @param available 当前分支是否至少有一个 Git 检查点
 * @param currentCheckpointEntryId 当前叶子所在 RUN 节点的检查点；叶子是回溯简报时为 null
 * @param nodes 按分支位置排列的节点
 */
public record SessionTimeline(boolean available, EntryId currentCheckpointEntryId,
        List<Node> nodes) {

    /** 节点类型。 */
    public enum NodeKind {
        RUN,
        REWIND
    }

    /**
     * 一个决策点。
     *
     * @param kind 节点类型
     * @param checkpointEntryId 检查点 entry；退化节点为 null
     * @param runId run 标识；退化节点可能为空串
     * @param at 节点时间
     * @param current 是否包含当前叶子
     * @param rewindable 是否允许回退
     * @param run RUN 节点的证据；REWIND 节点为 null
     * @param rewind 回溯简报；RUN 节点为 null
     */
    public record Node(NodeKind kind, EntryId checkpointEntryId, String runId, Instant at,
            boolean current, boolean rewindable, RunEvidence run, RewindEvidence rewind) {
    }

    /**
     * RUN 节点的证据。
     *
     * @param intent 触发这次 run 的用户文本
     * @param stats 汇总统计
     * @param stopReason 停止原因；尚未结束时为空串
     * @param turns 轮数；没有 run 摘要时为 0
     * @param toolCalls 工具调用数；没有 run 摘要时为 0
     * @param planItems 节点结束时的待办快照
     * @param approvals 节点内的审批
     * @param verifications 节点内的自动验证
     * @param changes 节点内的逐轮改动
     */
    public record RunEvidence(String intent, Stats stats, String stopReason,
            int turns, int toolCalls, List<PlanItem> planItems,
            List<ApprovalPayload> approvals, List<VerificationPayload> verifications,
            List<GitChangeSetPayload> changes) {
    }

    /**
     * REWIND 节点的简报。
     *
     * @param brief 摘要正文
     * @param discardedEntries 被丢弃的原始 entry 数
     * @param discardedRuns 被丢弃的 run 数
     */
    public record RewindEvidence(String brief, int discardedEntries, int discardedRuns) {
    }

    /**
     * RUN 节点统计。
     *
     * @param plan 计划
     * @param approvals 审批
     * @param verification 验证
     * @param changes 改动
     */
    public record Stats(PlanSummary plan, ApprovalSummary approvals,
            VerificationSummary verification, ChangeSummary changes) {
    }

    /** 计划统计。 */
    public record PlanSummary(int total, int done) {
    }

    /** 审批统计。 */
    public record ApprovalSummary(int total, int denied) {
    }

    /** 验证统计。 */
    public record VerificationSummary(int total, int failed) {
    }

    /**
     * 改动统计。
     *
     * @param turns 有改动的轮数
     * @param files 不同文件数
     * @param additions 新增行
     * @param deletions 删除行
     */
    public record ChangeSummary(int turns, int files, int additions, int deletions) {
    }
}
```

- [ ] **Step 4: Create the projection service**

```java
package com.piagent.application.timeline;

import com.piagent.domain.plan.model.PlanItem;
import com.piagent.domain.plan.model.PlanItemStatus;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.SessionStatus;
import com.piagent.domain.session.model.message.TextBlock;
import com.piagent.domain.session.model.message.UserMessage;
import com.piagent.domain.session.model.payload.ApprovalPayload;
import com.piagent.domain.session.model.payload.BranchSummaryPayload;
import com.piagent.domain.session.model.payload.GitChangeSetPayload;
import com.piagent.domain.session.model.payload.GitCheckpointPayload;
import com.piagent.domain.session.model.payload.MessagePayload;
import com.piagent.domain.session.model.payload.PlanPayload;
import com.piagent.domain.session.model.payload.RunSummaryPayload;
import com.piagent.domain.session.model.payload.VerificationPayload;
import com.piagent.domain.session.port.SessionEntryStore;
import com.piagent.domain.session.port.SessionRepository;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 把当前分支投影成决策时间线，只读且不访问磁盘。
 */
@Service
public class SessionTimelineService {

    private static final int MAX_INTENT = 400;

    private final SessionRepository sessions;
    private final SessionEntryStore entries;

    public SessionTimelineService(SessionRepository sessions, SessionEntryStore entries) {
        this.sessions = sessions;
        this.entries = entries;
    }

    /**
     * 查询当前分支的时间线。
     *
     * @param sessionId 会话标识
     * @return 时间线；空会话返回 available=false 且无节点
     */
    public SessionTimeline timeline(SessionId sessionId) {
        ChatSession session = require(sessionId);
        List<SessionEntry> branch = entries.loadBranch(sessionId, session.leafEntryId());
        if (branch.isEmpty()) {
            return new SessionTimeline(false, null, List.of());
        }
        boolean hasCheckpoint = branch.stream()
                .anyMatch(entry -> entry.payload() instanceof GitCheckpointPayload);
        boolean idle = session.status() == SessionStatus.IDLE;
        List<IndexedNode> indexed = hasCheckpoint
                ? checkpointNodes(branch, idle)
                : fallbackNodes(branch);
        if (indexed.isEmpty()) {
            return new SessionTimeline(hasCheckpoint, null, List.of());
        }
        int last = indexed.size() - 1;
        IndexedNode tail = indexed.get(last);
        boolean currentIsRun = tail.node().kind() == SessionTimeline.NodeKind.RUN;
        List<SessionTimeline.Node> nodes = new ArrayList<>();
        EntryId currentCheckpoint = null;
        for (int index = 0; index < indexed.size(); index++) {
            SessionTimeline.Node node = indexed.get(index).node();
            boolean current = index == last;
            if (current && currentIsRun) {
                currentCheckpoint = node.checkpointEntryId();
            }
            nodes.add(new SessionTimeline.Node(node.kind(), node.checkpointEntryId(),
                    node.runId(), node.at(), current, node.rewindable(), node.run(),
                    node.rewind()));
        }
        return new SessionTimeline(hasCheckpoint, currentCheckpoint, List.copyOf(nodes));
    }

    private List<IndexedNode> checkpointNodes(List<SessionEntry> branch, boolean idle) {
        List<IndexedNode> nodes = new ArrayList<>();
        for (int index = 0; index < branch.size(); index++) {
            Object payload = branch.get(index).payload();
            if (payload instanceof GitCheckpointPayload checkpoint) {
                int end = nextCheckpoint(branch, index + 1);
                nodes.add(new IndexedNode(index, runNode(branch, new RunSlice(index, end,
                        checkpoint.runId(), branch.get(index), branch.get(index), idle))));
            } else if (payload instanceof BranchSummaryPayload summary && isRewind(summary)) {
                nodes.add(new IndexedNode(index, rewindNode(branch.get(index), summary)));
            }
        }
        return nodes;
    }

    private List<IndexedNode> fallbackNodes(List<SessionEntry> branch) {
        List<IndexedNode> nodes = new ArrayList<>();
        int start = 0;
        for (int index = 0; index < branch.size(); index++) {
            if (branch.get(index).payload() instanceof RunSummaryPayload summary) {
                int userIndex = firstUserMessage(branch, start, index + 1);
                SessionEntry at = branch.get(userIndex >= 0 ? userIndex : index);
                nodes.add(new IndexedNode(index, runNode(branch, new RunSlice(start,
                        index + 1, summary.runId(), null, at, false))));
                start = index + 1;
            }
        }
        if (start < branch.size()) {
            int userIndex = firstUserMessage(branch, start, branch.size());
            if (userIndex >= 0) {
                nodes.add(new IndexedNode(branch.size() - 1, runNode(branch, new RunSlice(start,
                        branch.size(), "", null, branch.get(userIndex), false))));
            }
        }
        for (int index = 0; index < branch.size(); index++) {
            if (branch.get(index).payload() instanceof BranchSummaryPayload summary
                    && isRewind(summary)) {
                nodes.add(new IndexedNode(index, rewindNode(branch.get(index), summary)));
            }
        }
        nodes.sort(Comparator.comparingInt(IndexedNode::index));
        return nodes;
    }

    private SessionTimeline.Node runNode(List<SessionEntry> branch, RunSlice slice) {
        RunEvidence evidence = evidence(branch, slice.start(), slice.end(), slice.runId());
        EntryId checkpointId = slice.checkpointEntry() == null ? null : slice.checkpointEntry().id();
        return new SessionTimeline.Node(SessionTimeline.NodeKind.RUN, checkpointId, slice.runId(),
                slice.atEntry().createdAt(), false, slice.rewindable(), evidence, null);
    }

    private RunEvidence evidence(List<SessionEntry> branch, int start, int end, String runId) {
        PlanPayload latestPlan = null;
        List<ApprovalPayload> approvals = new ArrayList<>();
        List<VerificationPayload> verifications = new ArrayList<>();
        List<GitChangeSetPayload> changes = new ArrayList<>();
        String stopReason = "";
        int turns = 0;
        int toolCalls = 0;
        for (int index = start; index < end; index++) {
            Object payload = branch.get(index).payload();
            if (payload instanceof PlanPayload plan) {
                latestPlan = plan;
            } else if (payload instanceof ApprovalPayload approval) {
                approvals.add(approval);
            } else if (payload instanceof VerificationPayload verification
                    && matches(verification.runId(), runId)) {
                verifications.add(verification);
            } else if (payload instanceof GitChangeSetPayload change
                    && matches(change.runId(), runId)) {
                changes.add(change);
            } else if (payload instanceof RunSummaryPayload summary
                    && matches(summary.runId(), runId)) {
                stopReason = summary.stopReason();
                turns = summary.turns();
                toolCalls = summary.toolCalls();
            }
        }
        List<PlanItem> planItems = latestPlan == null ? List.of() : latestPlan.items();
        int done = (int) planItems.stream()
                .filter(item -> item.status() == PlanItemStatus.DONE).count();
        int denied = (int) approvals.stream()
                .filter(item -> "DENIED".equalsIgnoreCase(item.decision())).count();
        int failed = (int) verifications.stream().filter(item -> !item.passed()).count();
        Set<String> paths = new LinkedHashSet<>();
        int additions = 0;
        int deletions = 0;
        for (GitChangeSetPayload change : changes) {
            for (var file : change.files()) {
                paths.add(file.path());
                additions += file.additions();
                deletions += file.deletions();
            }
        }
        SessionTimeline.Stats stats = new SessionTimeline.Stats(
                new SessionTimeline.PlanSummary(planItems.size(), done),
                new SessionTimeline.ApprovalSummary(approvals.size(), denied),
                new SessionTimeline.VerificationSummary(verifications.size(), failed),
                new SessionTimeline.ChangeSummary(changes.size(), paths.size(), additions,
                        deletions));
        return new RunEvidence(intent(branch, start, end), stats, stopReason, turns, toolCalls,
                planItems, List.copyOf(approvals), List.copyOf(verifications),
                List.copyOf(changes));
    }

    private String intent(List<SessionEntry> branch, int start, int end) {
        for (int index = start; index < end; index++) {
            if (branch.get(index).payload() instanceof MessagePayload message
                    && message.message() instanceof UserMessage user) {
                String text = textOf(user);
                if (!text.isBlank()) {
                    return truncate(text, MAX_INTENT);
                }
            }
        }
        for (int index = Math.min(start - 1, branch.size() - 1); index >= 0; index--) {
            if (branch.get(index).payload() instanceof MessagePayload message
                    && message.message() instanceof UserMessage user) {
                String text = textOf(user);
                if (!text.isBlank()) {
                    return truncate(text, MAX_INTENT);
                }
            }
        }
        return "";
    }

    private String textOf(UserMessage user) {
        StringBuilder text = new StringBuilder();
        for (var block : user.content()) {
            if (block instanceof TextBlock content) {
                text.append(content.text());
            }
        }
        if (text.toString().isBlank() && !user.content().isEmpty()) {
            return "（图片消息）";
        }
        return text.toString().strip();
    }

    private int firstUserMessage(List<SessionEntry> branch, int start, int end) {
        for (int index = start; index < end; index++) {
            if (branch.get(index).payload() instanceof MessagePayload message
                    && message.message() instanceof UserMessage) {
                return index;
            }
        }
        return -1;
    }

    private int nextCheckpoint(List<SessionEntry> branch, int start) {
        for (int index = start; index < branch.size(); index++) {
            if (branch.get(index).payload() instanceof GitCheckpointPayload) {
                return index;
            }
        }
        return branch.size();
    }

    private SessionTimeline.Node rewindNode(SessionEntry entry, BranchSummaryPayload payload) {
        Map<String, Object> details = payload.details();
        return new SessionTimeline.Node(SessionTimeline.NodeKind.REWIND, null,
                text(details.get("runId")), entry.createdAt(), false, false, null,
                new SessionTimeline.RewindEvidence(payload.summary(),
                        number(details.get("discardedEntries")),
                        number(details.get("discardedRuns"))));
    }

    private boolean isRewind(BranchSummaryPayload payload) {
        return "rewind".equals(payload.details().get("kind"));
    }

    private boolean matches(String value, String runId) {
        return runId.isEmpty() || runId.equals(value);
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private int number(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException error) {
            return 0;
        }
    }

    private String truncate(String text, int limit) {
        return text.length() <= limit ? text : text.substring(0, limit - 1) + "…";
    }

    private ChatSession require(SessionId sessionId) {
        return sessions.findById(sessionId)
                .orElseThrow(() -> new DomainException(DomainErrorCode.SESSION_NOT_FOUND,
                        "会话不存在: " + sessionId));
    }

    private record IndexedNode(int index, SessionTimeline.Node node) {
    }

    private record RunSlice(int start, int end, String runId, SessionEntry checkpointEntry,
            SessionEntry atEntry, boolean rewindable) {
    }
}
```

- [ ] **Step 5: Run the tests and confirm they pass**

```bash
cd D:/code/pi-java
JAVA_HOME="C:/Users/R9000P/.jdks/ms-21.0.10" mvn -o -pl pi-java-application -am test \
  -Dtest=SessionTimelineServiceTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 6: Commit**

```bash
cd D:/code/pi-java
git add pi-java-application/src/main/java/com/piagent/application/timeline/SessionTimeline.java \
  pi-java-application/src/main/java/com/piagent/application/timeline/SessionTimelineService.java \
  pi-java-application/src/test/java/com/piagent/application/timeline/SessionTimelineServiceTest.java
git commit -m "feat(m22): project decision timeline from session branch"
```

---

## Task 5: Build the deterministic rewind brief

**Files:**
- Create: `pi-java-application/src/main/java/com/piagent/application/timeline/RewindBrief.java`
- Create: `pi-java-application/src/main/java/com/piagent/application/timeline/RewindBriefBuilder.java`
- Test: `pi-java-application/src/test/java/com/piagent/application/timeline/RewindBriefBuilderTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.piagent.application.timeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.application.testsupport.SessionEntryFactory;
import com.piagent.domain.plan.model.PlanItem;
import com.piagent.domain.plan.model.PlanItemStatus;
import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.message.UserMessage;
import com.piagent.domain.session.model.payload.ApprovalPayload;
import com.piagent.domain.session.model.payload.GitCheckpointPayload;
import com.piagent.domain.session.model.payload.MessagePayload;
import com.piagent.domain.session.model.payload.PlanPayload;
import com.piagent.domain.session.model.payload.VerificationPayload;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class RewindBriefBuilderTest {

    private static final SessionId SESSION_ID = SessionEntryFactory.sessionId(1);
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-11-05T14:32:00Z"), ZoneOffset.UTC);
    private final RewindBriefBuilder builder = new RewindBriefBuilder(CLOCK);

    @Test
    void rendersCountsAndAvoidList() {
        SessionEntry checkpoint = SessionEntryFactory.entry(SESSION_ID, 2, null,
                new GitCheckpointPayload("run-1", null, "tree-0", "head", "main"), 0);
        SessionEntry user = SessionEntryFactory.message(SESSION_ID, 1, null,
                new UserMessage(List.of(new com.piagent.domain.session.model.message.TextBlock(
                        "修复登录")), Instant.parse("2026-11-05T14:30:00Z")), 8);
        SessionEntry denied = SessionEntryFactory.entry(SESSION_ID, 3, null,
                new ApprovalPayload("a1", "c1", "write", "DENIED", "先别改", "user"), 2);
        SessionEntry failed = SessionEntryFactory.entry(SESSION_ID, 4, null,
                new VerificationPayload("run-1", "mvn test", false, 1, false, false, 120,
                        "fail"), 2);
        SessionEntry plan = SessionEntryFactory.entry(SESSION_ID, 5, null,
                new PlanPayload("run-1", List.of(
                        new PlanItem("改登录", PlanItemStatus.DONE),
                        new PlanItem("补登录测试", PlanItemStatus.PENDING))), 2);

        RewindBrief brief = builder.build(checkpoint,
                List.of(user, checkpoint, denied, failed, plan), List.of("a.txt", "b.txt"),
                "换一种修法");

        assertThat(brief.summary())
                .contains("从 14:32 的决策点回退")
                .contains("丢弃 1 轮对话")
                .contains("1 次被拒绝的审批")
                .contains("1 次失败的验证")
                .contains("已还原 2 个文件")
                .contains("未完成：补登录测试")
                .contains("write（已拒绝）")
                .contains("mvn test（已验证失败）")
                .contains("备注：换一种修法");
        assertThat(brief.summary().length()).isLessThanOrEqualTo(400);
        assertThat(brief.details())
                .containsEntry("kind", "rewind")
                .containsEntry("discardedEntries", 5)
                .containsEntry("discardedRuns", 1)
                .containsEntry("restoredFiles", 2)
                .containsEntry("deniedApprovals", 1)
                .containsEntry("failedVerifications", 1)
                .containsEntry("unfinishedPlanItems", 1);
    }

    @Test
    void rejectsTooLongNote() {
        SessionEntry checkpoint = SessionEntryFactory.entry(SESSION_ID, 1, null,
                new GitCheckpointPayload("run-1", null, "tree-0", "head", "main"), 0);

        assertThatThrownBy(() -> builder.build(checkpoint, List.of(), List.of(), "x".repeat(201)))
                .isInstanceOfSatisfying(DomainException.class, error ->
                        assertThat(error.code()).isEqualTo(DomainErrorCode.INVALID_ARGUMENT));
    }
}
```

- [ ] **Step 2: Run the test and confirm failure**

```bash
cd D:/code/pi-java
JAVA_HOME="C:/Users/R9000P/.jdks/ms-21.0.10" mvn -o -pl pi-java-application -am test \
  -Dtest=RewindBriefBuilderTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: compilation fails because `RewindBrief` and `RewindBriefBuilder` do not exist.

- [ ] **Step 3: Create `RewindBrief`**

```java
package com.piagent.application.timeline;

import java.util.Map;

/**
 * 回退后写入会话的结构化简报。
 *
 * @param summary 不超过 400 字的摘要
 * @param details 结构化细节，写入 {@code BranchSummaryPayload.details}
 */
public record RewindBrief(String summary, Map<String, Object> details) {
}
```

- [ ] **Step 4: Create `RewindBriefBuilder`**

```java
package com.piagent.application.timeline;

import com.piagent.domain.plan.model.PlanItem;
import com.piagent.domain.plan.model.PlanItemStatus;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.message.UserMessage;
import com.piagent.domain.session.model.payload.ApprovalPayload;
import com.piagent.domain.session.model.payload.GitCheckpointPayload;
import com.piagent.domain.session.model.payload.MessagePayload;
import com.piagent.domain.session.model.payload.PlanPayload;
import com.piagent.domain.session.model.payload.VerificationPayload;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 只用结构数据生成回溯简报，不调用 LLM，也不包含文件内容或完整验证输出。
 */
@Component
public class RewindBriefBuilder {

    private static final int MAX_SUMMARY = 400;
    private static final int MAX_NOTE = 200;

    private final DateTimeFormatter timeFormat;

    public RewindBriefBuilder(Clock clock) {
        this.timeFormat = DateTimeFormatter.ofPattern("HH:mm").withZone(clock.getZone());
    }

    /**
     * 生成简报。
     *
     * @param checkpointEntry 目标检查点 entry
     * @param discarded 将被丢弃的 entry
     * @param restoredFiles 将还原的路径
     * @param note 用户备注，可为 null
     * @return 摘要与 details
     */
    public RewindBrief build(SessionEntry checkpointEntry, List<SessionEntry> discarded,
            List<String> restoredFiles, String note) {
        String cleanNote = requireNote(note);
        GitCheckpointPayload checkpoint = (GitCheckpointPayload) checkpointEntry.payload();
        Scan scan = scan(discarded);
        List<String> unfinished = unfinished(discarded);
        Facts facts = new Facts(scan.userMessages(), scan.runs(), scan.denied(), scan.failed(),
                restoredFiles.size(), unfinished, scan.deniedTools(), scan.failedCommands(),
                cleanNote);
        String summary = render(checkpointEntry, facts);
        return new RewindBrief(summary, details(checkpointEntry, checkpoint, discarded, facts));
    }

    private String requireNote(String note) {
        String cleanNote = note == null ? "" : note.strip();
        if (cleanNote.length() > MAX_NOTE) {
            throw new DomainException(DomainErrorCode.INVALID_ARGUMENT, "备注不能超过 200 字");
        }
        return cleanNote;
    }

    private Scan scan(List<SessionEntry> discarded) {
        int userMessages = 0;
        int runs = 0;
        Set<String> deniedTools = new LinkedHashSet<>();
        Set<String> failedCommands = new LinkedHashSet<>();
        int denied = 0;
        int failed = 0;
        for (SessionEntry entry : discarded) {
            Object payload = entry.payload();
            if (payload instanceof MessagePayload message
                    && message.message() instanceof UserMessage) {
                userMessages++;
            } else if (payload instanceof GitCheckpointPayload) {
                runs++;
            } else if (payload instanceof ApprovalPayload approval
                    && "DENIED".equalsIgnoreCase(approval.decision())) {
                denied++;
                deniedTools.add(approval.toolName());
            } else if (payload instanceof VerificationPayload verification
                    && !verification.passed()) {
                failed++;
                failedCommands.add(verification.command());
            }
        }
        return new Scan(userMessages, runs, denied, failed, List.copyOf(deniedTools),
                List.copyOf(failedCommands));
    }

    private List<String> unfinished(List<SessionEntry> discarded) {
        PlanPayload latestPlan = null;
        for (SessionEntry entry : discarded) {
            if (entry.payload() instanceof PlanPayload plan) {
                latestPlan = plan;
            }
        }
        return latestPlan == null ? List.of() : latestPlan.items().stream()
                .filter(item -> item.status() != PlanItemStatus.DONE)
                .map(PlanItem::content)
                .toList();
    }

    private Map<String, Object> details(SessionEntry checkpointEntry,
            GitCheckpointPayload checkpoint, List<SessionEntry> discarded, Facts facts) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("kind", "rewind");
        details.put("checkpointEntryId", checkpointEntry.id().toString());
        details.put("runId", checkpoint.runId());
        details.put("discardedEntries", discarded.size());
        details.put("discardedRuns", facts.runs());
        details.put("restoredFiles", facts.restoredFiles());
        details.put("deniedApprovals", facts.denied());
        details.put("failedVerifications", facts.failed());
        details.put("unfinishedPlanItems", facts.unfinished().size());
        return Map.copyOf(details);
    }

    private String render(SessionEntry checkpointEntry, Facts facts) {
        StringBuilder text = new StringBuilder();
        text.append("从 ").append(timeFormat.format(checkpointEntry.createdAt()))
                .append(" 的决策点回退：丢弃 ").append(facts.userMessages()).append(" 轮对话、")
                .append(facts.denied()).append(" 次被拒绝的审批、").append(facts.failed())
                .append(" 次失败的验证。");
        text.append("已还原 ").append(facts.restoredFiles()).append(" 个文件");
        if (!facts.unfinished().isEmpty()) {
            text.append("；未完成：").append(joinLimit(facts.unfinished(), 3));
        }
        text.append("。");
        List<String> avoid = new ArrayList<>();
        facts.deniedTools().stream().limit(3).forEach(tool -> avoid.add(tool + "（已拒绝）"));
        facts.failedCommands().stream().limit(3)
                .forEach(command -> avoid.add(command + "（已验证失败）"));
        if (!avoid.isEmpty()) {
            text.append("继续时不要重复：").append(String.join("、", avoid)).append("。");
        }
        if (!facts.note().isBlank()) {
            text.append("备注：").append(facts.note()).append("。");
        }
        String summary = text.toString();
        return summary.length() <= MAX_SUMMARY ? summary
                : summary.substring(0, MAX_SUMMARY - 1) + "…";
    }

    private String joinLimit(List<String> values, int limit) {
        String joined = String.join("、", values.stream().limit(limit).toList());
        return values.size() > limit ? joined + " 等" : joined;
    }

    private record Scan(int userMessages, int runs, int denied, int failed,
            List<String> deniedTools, List<String> failedCommands) {
    }

    private record Facts(int userMessages, int runs, int denied, int failed, int restoredFiles,
            List<String> unfinished, List<String> deniedTools, List<String> failedCommands,
            String note) {
    }
}
```

- [ ] **Step 5: Run the test and confirm it passes**

```bash
cd D:/code/pi-java
JAVA_HOME="C:/Users/R9000P/.jdks/ms-21.0.10" mvn -o -pl pi-java-application -am test \
  -Dtest=RewindBriefBuilderTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 6: Commit**

```bash
cd D:/code/pi-java
git add pi-java-application/src/main/java/com/piagent/application/timeline/RewindBrief.java \
  pi-java-application/src/main/java/com/piagent/application/timeline/RewindBriefBuilder.java \
  pi-java-application/src/test/java/com/piagent/application/timeline/RewindBriefBuilderTest.java
git commit -m "feat(m22): build deterministic rewind briefs"
```

---

## Task 6: Preview and execute rewinds

**Files:**
- Create: `pi-java-application/src/main/java/com/piagent/application/timeline/SessionRewindResult.java`
- Create: `pi-java-application/src/main/java/com/piagent/application/timeline/SessionRewindService.java`
- Test: `pi-java-application/src/test/java/com/piagent/application/timeline/SessionRewindServiceTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.piagent.application.timeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.application.agent.service.SessionEntryAppender;
import com.piagent.application.git.GitCheckpointService;
import com.piagent.application.testsupport.InMemorySessionEntryStore;
import com.piagent.application.testsupport.InMemorySessionRepository;
import com.piagent.application.testsupport.SequentialIdGenerator;
import com.piagent.domain.agent.RunId;
import com.piagent.domain.git.model.FileChange;
import com.piagent.domain.git.model.GitChangeKind;
import com.piagent.domain.git.model.GitChangeSet;
import com.piagent.domain.git.model.GitConflict;
import com.piagent.domain.git.model.GitRestorePlan;
import com.piagent.domain.git.model.GitRestoreResult;
import com.piagent.domain.git.model.GitSnapshot;
import com.piagent.domain.git.port.WorkspaceGit;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.EntryType;
import com.piagent.domain.session.model.SandboxSnapshot;
import com.piagent.domain.session.model.SessionConfig;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.SessionStatus;
import com.piagent.domain.session.model.ThinkingLevel;
import com.piagent.domain.session.model.TokenUsage;
import com.piagent.domain.session.model.payload.GitCheckpointPayload;
import com.piagent.domain.session.model.payload.RunSummaryPayload;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SessionRewindServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-05T14:00:00Z");
    private static final Path WORKSPACE = Path.of("target/rewind-unit").toAbsolutePath();

    private final InMemorySessionRepository sessions = new InMemorySessionRepository();
    private final InMemorySessionEntryStore entries = new InMemorySessionEntryStore();
    private final SequentialIdGenerator ids = new SequentialIdGenerator();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final SessionEntryAppender appender = new SessionEntryAppender(sessions, entries, ids,
            clock);
    private final FakeGit git = new FakeGit();
    private final GitCheckpointService checkpoints = new GitCheckpointService(git, sessions,
            entries, appender);
    private final BranchSummaryAppender summaries = new BranchSummaryAppender(sessions, entries, ids,
            clock);
    private final SessionRewindService service = new SessionRewindService(sessions, entries, git,
            summaries, new RewindBriefBuilder(clock));

    private SessionId sessionId;

    @BeforeEach
    void setUp() {
        sessionId = SessionId.newId(ids);
        sessions.insert(ChatSession.createRoot(sessionId, "回退测试",
                new SessionConfig(WORKSPACE, "fake-demo", ThinkingLevel.OFF,
                        SandboxSnapshot.forWorkspace(WORKSPACE))));
    }

    @Test
    void previewDoesNotWriteOrAppend() {
        RunIds runs = threeRuns();
        long before = entries.countBySession(sessionId);
        EntryId leafBefore = sessions.findById(sessionId).orElseThrow().leafEntryId();
        git.nextPlan = new GitRestorePlan("tree-0", "tree-2",
                List.of("a.txt"), List.of());

        SessionRewindResult result = service.rewind(sessionId, runs.secondCheckpoint, true, null);

        assertThat(result.dryRun()).isTrue();
        assertThat(result.rewound()).isFalse();
        assertThat(result.restorable()).isTrue();
        assertThat(result.files()).containsExactly("a.txt");
        assertThat(result.discardedEntries()).isPositive();
        assertThat(result.discardedRuns()).isPositive();
        assertThat(entries.countBySession(sessionId)).isEqualTo(before);
        assertThat(sessions.findById(sessionId).orElseThrow().leafEntryId()).isEqualTo(leafBefore);
        assertThat(git.restoreCalled).isFalse();
    }

    @Test
    void rewindsToMiddleCheckpointAndAppendsBrief() {
        RunIds runs = threeRuns();
        EntryId oldLeaf = sessions.findById(sessionId).orElseThrow().leafEntryId();
        git.nextPlan = new GitRestorePlan("tree-0", "tree-2",
                List.of("a.txt"), List.of());
        git.nextRestore = new GitRestoreResult(List.of("a.txt"), List.of());

        SessionRewindResult result = service.rewind(sessionId, runs.secondCheckpoint, false, null);

        assertThat(result.rewound()).isTrue();
        assertThat(result.restored()).containsExactly("a.txt");
        SessionEntry brief = entries.findById(sessionId, result.briefEntryId()).orElseThrow();
        assertThat(brief.type()).isEqualTo(EntryType.BRANCH_SUMMARY);
        assertThat(brief.parentId()).isEqualTo(runs.secondBaseLeaf);
        assertThat(sessions.findById(sessionId).orElseThrow().leafEntryId()).isEqualTo(brief.id());
        assertThat(brief.payload().toString()).contains("kind=rewind");
        assertThat(entries.countBySession(sessionId))
                .isGreaterThan((long) branchSize() - 1);
        List<SessionEntry> branch = entries.loadBranch(sessionId, brief.id());
        assertThat(branch).contains(brief);
        assertThat(branch).noneMatch(entry -> entry.id().equals(runs.thirdCheckpoint));
        assertThat(entries.findById(sessionId, runs.thirdCheckpoint)).isPresent();
        assertThat(entries.findById(sessionId, oldLeaf)).isPresent();
        assertThat(oldLeaf).isNotEqualTo(brief.id());
    }

    @Test
    void rewindsFirstRunToRoot() {
        RunIds runs = threeRuns();
        git.nextPlan = new GitRestorePlan("tree-0", "tree-2", List.of("a.txt"), List.of());
        git.nextRestore = new GitRestoreResult(List.of("a.txt"), List.of());

        SessionRewindResult result = service.rewind(sessionId, runs.firstCheckpoint, false,
                "换一种修法");

        assertThat(result.rewound()).isTrue();
        assertThat(entries.countBySession(sessionId)).isEqualTo(1);
        SessionEntry brief = entries.findById(sessionId, result.briefEntryId()).orElseThrow();
        assertThat(brief.seq()).isEqualTo(1);
        assertThat(brief.parentId()).isNull();
        assertThat(result.brief()).contains("备注：换一种修法");
    }

    @Test
    void conflictKeepsEverythingUntouched() {
        RunIds runs = threeRuns();
        long before = entries.countBySession(sessionId);
        EntryId leafBefore = sessions.findById(sessionId).orElseThrow().leafEntryId();
        git.nextPlan = new GitRestorePlan("tree-0", "tree-2", List.of("a.txt"),
                List.of(new GitConflict("a.txt", "目标之后工作区又发生了变化，为避免覆盖已跳过")));

        SessionRewindResult result = service.rewind(sessionId, runs.secondCheckpoint, false, null);

        assertThat(result.rewound()).isFalse();
        assertThat(result.restorable()).isFalse();
        assertThat(result.conflicts()).hasSize(1);
        assertThat(result.discardedEntries()).isZero();
        assertThat(entries.countBySession(sessionId)).isEqualTo(before);
        assertThat(sessions.findById(sessionId).orElseThrow().leafEntryId()).isEqualTo(leafBefore);
        assertThat(git.restoreCalled).isFalse();
    }

    @Test
    void rejectsRunningArchivedAndForeignCheckpoint() {
        RunIds runs = threeRuns();
        EntryId foreign = EntryId.parse("01TST000000000000000000099");
        assertThatThrownBy(() -> service.rewind(sessionId, foreign, true, null))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.INVALID_BRANCH));

        ChatSession archived = sessions.findById(sessionId).orElseThrow().archive();
        sessions.update(archived);
        assertThatThrownBy(() -> service.rewind(sessionId, runs.secondCheckpoint, true, null))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.SESSION_ARCHIVED));
    }

    @Test
    void rejectsWhileRunning() {
        RunIds runs = threeRuns();
        EntryId baseLeaf = entryIdBeforeUser();
        appender.appendUserAndStart(sessionId, "又开始跑");
        checkpoints.beginRun(sessionId, WORKSPACE, RunId.newId(ids), baseLeaf);

        assertThatThrownBy(() -> service.rewind(sessionId, runs.secondCheckpoint, true, null))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.INVALID_STATE_TRANSITION));
    }

    private EntryId entryIdBeforeUser() {
        return sessions.findById(sessionId).orElseThrow().leafEntryId();
    }

    private RunIds threeRuns() {
        git.snapshots.add(new GitSnapshot("tree-0", "head", "main"));
        appender.appendUserAndStart(sessionId, "第一轮");
        EntryId firstCheckpoint = begin("run-1", null, "tree-0", "tree-1");
        EntryId firstLeaf = finish("run-1");

        appender.appendUserAndStart(sessionId, "第二轮");
        EntryId secondCheckpoint = begin("run-2", firstLeaf, "tree-1", "tree-2");
        EntryId secondLeaf = finish("run-2");

        appender.appendUserAndStart(sessionId, "第三轮");
        EntryId thirdCheckpoint = begin("run-3", secondLeaf, "tree-2", "tree-3");
        finish("run-3");
        return new RunIds(firstCheckpoint, firstLeaf, secondCheckpoint, thirdCheckpoint);
    }

    private EntryId begin(String runId, EntryId baseLeaf, String baseTree, String endTree) {
        git.snapshots.add(new GitSnapshot(baseTree, "head", "main"));
        checkpoints.beginRun(sessionId, WORKSPACE, RunId.parse(runId), baseLeaf);
        git.snapshots.add(new GitSnapshot(endTree, "head", "main"));
        git.nextChanges = new GitChangeSet(baseTree, endTree,
                List.of(new FileChange("a.txt", GitChangeKind.MODIFIED, 1, 0, false)),
                "diff", false);
        checkpoints.endTurn(sessionId, WORKSPACE, RunId.parse(runId), 1, baseLeaf, baseTree);
        return branch().stream().filter(entry -> entry.payload()
                instanceof GitCheckpointPayload checkpoint
                && checkpoint.runId().equals(runId)).findFirst().orElseThrow().id();
    }

    private EntryId finish(String runId) {
        appender.finishRun(sessionId, new RunSummaryPayload(runId, "COMPLETED",
                TokenUsage.zero(), 1, 0, 100, null));
        return branch().getLast().id();
    }

    private List<SessionEntry> branch() {
        return entries.loadBranch(sessionId,
                sessions.findById(sessionId).orElseThrow().leafEntryId());
    }

    private int branchSize() {
        return branch().size();
    }

    private record RunIds(EntryId firstCheckpoint, EntryId secondBaseLeaf,
            EntryId secondCheckpoint, EntryId thirdCheckpoint) {
    }

    private static final class FakeGit implements WorkspaceGit {

        private final Deque<GitSnapshot> snapshots = new ArrayDeque<>();
        private GitChangeSet nextChanges;
        private GitRestorePlan nextPlan = new GitRestorePlan("tree-0", "tree-0",
                List.of(), List.of());
        private GitRestoreResult nextRestore = new GitRestoreResult(List.of(), List.of());
        private boolean restoreCalled;

        @Override
        public Optional<GitSnapshot> snapshot(Path workspace) {
            return Optional.ofNullable(snapshots.pollFirst());
        }

        @Override
        public GitChangeSet changes(Path workspace, String baseTreeSha, String treeSha) {
            return nextChanges;
        }

        @Override
        public GitRestoreResult restore(Path workspace, String baseTreeSha, String treeSha) {
            restoreCalled = true;
            return nextRestore;
        }

        @Override
        public GitRestorePlan planRestore(Path workspace, String baseTreeSha, String treeSha) {
            return nextPlan;
        }

        @Override
        public void pinCheckpoint(Path workspace, String namespace, String checkpointId,
                String treeSha) {
        }

        @Override
        public void clearCheckpoints(Path workspace, String namespace) {
        }
    }
}
```

- [ ] **Step 2: Run the tests and confirm failure**

```bash
cd D:/code/pi-java
JAVA_HOME="C:/Users/R9000P/.jdks/ms-21.0.10" mvn -o -pl pi-java-application -am test \
  -Dtest=SessionRewindServiceTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: compilation fails because `SessionRewindResult` and `SessionRewindService` do not exist.

- [ ] **Step 3: Create `SessionRewindResult`**

```java
package com.piagent.application.timeline;

import com.piagent.domain.git.model.GitConflict;
import com.piagent.domain.session.model.EntryId;
import java.util.List;

/**
 * 一次回退请求的结果，预览与执行共用。
 *
 * <p>预览时 {@code dryRun=true, rewound=false}，{@code files} 是计划还原的路径；
 * 执行成功后 {@code files} 仍是计划路径，{@code restored} 才是真正写入的路径。
 * 冲突时 counts 为 0：没有任何 entry 被丢弃，调用方不应展示“已回退”文案。</p>
 *
 * @param dryRun 是否只是预览
 * @param rewound 是否已完成对话分支与工作区回退
 * @param restorable 工作区是否可以安全还原
 * @param files 计划还原的路径
 * @param restored 实际还原的路径
 * @param conflicts 冲突列表
 * @param briefEntryId 新写入的回溯简报 entry；预览或失败时为 null
 * @param discardedEntries 将丢弃的 entry 数
 * @param discardedRuns 将丢弃的 run 数
 * @param brief 简报正文；预览时也返回，供确认弹窗展示
 */
public record SessionRewindResult(boolean dryRun, boolean rewound, boolean restorable,
        List<String> files, List<String> restored, List<GitConflict> conflicts,
        EntryId briefEntryId, int discardedEntries, int discardedRuns, String brief) {
}
```

- [ ] **Step 4: Create `SessionRewindService`**

```java
package com.piagent.application.timeline;

import com.piagent.domain.git.model.GitRestorePlan;
import com.piagent.domain.git.model.GitRestoreResult;
import com.piagent.domain.git.port.WorkspaceGit;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.SessionStatus;
import com.piagent.domain.session.model.payload.BranchSummaryPayload;
import com.piagent.domain.session.model.payload.GitChangeSetPayload;
import com.piagent.domain.session.model.payload.GitCheckpointPayload;
import com.piagent.domain.session.port.SessionEntryStore;
import com.piagent.domain.session.port.SessionRepository;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 回退到任意检查点的用例。
 *
 * <p>目标树直接取检查点的 {@code baseTreeSha}；当前分支上最后一个已知树来自
 * 之后的改动或下一个检查点的起点。还原前先用只读计划检查冲突，冲突时一个字节
 * 都不写、一条 entry 都不删。成功后分支退回检查点记录的叶子，并追加一条回溯
 * 简报作为新分支的起点。</p>
 */
@Service
public class SessionRewindService {

    private static final int MAX_ERROR_LENGTH = 400;

    private final SessionRepository sessions;
    private final SessionEntryStore entries;
    private final WorkspaceGit git;
    private final BranchSummaryAppender summaries;
    private final RewindBriefBuilder briefs;

    /**
     * 创建回退服务。
     *
     * @param sessions 会话仓储
     * @param entries entry 存储
     * @param git 工作区 Git 端口
     * @param summaries 回溯简报追加器
     * @param briefs 简报生成器
     */
    public SessionRewindService(SessionRepository sessions, SessionEntryStore entries,
            WorkspaceGit git, BranchSummaryAppender summaries, RewindBriefBuilder briefs) {
        this.sessions = sessions;
        this.entries = entries;
        this.git = git;
        this.summaries = summaries;
        this.briefs = briefs;
    }

    /**
     * 预览或执行一次回退。
     *
     * @param sessionId 会话标识
     * @param checkpointEntryId 目标检查点 entry
     * @param dryRun true 表示只预览
     * @param note 用户备注，可为 null
     * @return 预览或执行结果
     */
    @Transactional
    public SessionRewindResult rewind(SessionId sessionId, EntryId checkpointEntryId,
            boolean dryRun, String note) {
        Objects.requireNonNull(checkpointEntryId, "checkpointEntryId 不能为空");
        ChatSession session = require(sessionId);
        if (session.status() == SessionStatus.ARCHIVED) {
            throw new DomainException(DomainErrorCode.SESSION_ARCHIVED, "已归档会话不能回退");
        }
        if (session.status() != SessionStatus.IDLE) {
            throw new DomainException(DomainErrorCode.INVALID_STATE_TRANSITION,
                    "只有空闲会话可以回退，当前状态: " + session.status());
        }
        List<SessionEntry> branch = entries.loadBranch(sessionId, session.leafEntryId());
        int index = indexOf(branch, checkpointEntryId);
        if (index < 0 || !(branch.get(index).payload() instanceof GitCheckpointPayload checkpoint)) {
            throw new DomainException(DomainErrorCode.INVALID_BRANCH,
                    "目标不是当前分支上的检查点: " + checkpointEntryId);
        }
        SessionEntry checkpointEntry = branch.get(index);
        int discardStart = discardStart(branch, checkpoint.baseLeafId());
        List<SessionEntry> discarded = List.copyOf(branch.subList(discardStart, branch.size()));
        String latestTree = latestTree(branch, index, checkpoint.baseTreeSha());
        GitRestorePlan plan = plan(session, checkpoint.baseTreeSha(), latestTree);
        RewindBrief preview = briefs.build(checkpointEntry, discarded, plan.files(), note);
        if (dryRun) {
            return new SessionRewindResult(true, false, plan.restorable(), plan.files(), List.of(),
                    plan.conflicts(), null, discarded.size(), countRuns(discarded),
                    preview.summary());
        }
        if (!plan.restorable()) {
            return conflict(plan);
        }
        GitRestoreResult restored = restore(session, checkpoint.baseTreeSha(), latestTree);
        if (!restored.complete()) {
            return new SessionRewindResult(false, false, false, plan.files(), List.of(),
                    restored.conflicts(), null, 0, 0, "");
        }
        EntryId oldLeaf = session.leafEntryId();
        RewindBrief brief = briefs.build(checkpointEntry, discarded, restored.restored(), note);
        if (checkpoint.baseLeafId() == null) {
            entries.deleteAll(sessionId);
        }
        sessions.update(session.rewind(checkpoint.baseLeafId()));
        SessionEntry briefEntry = summaries.append(sessionId,
                new BranchSummaryPayload(oldLeaf, brief.summary(), brief.details()));
        return new SessionRewindResult(false, true, true, plan.files(), restored.restored(),
                List.of(), briefEntry.id(), discarded.size(), countRuns(discarded),
                brief.summary());
    }

    private SessionRewindResult conflict(GitRestorePlan plan) {
        return new SessionRewindResult(false, false, false, plan.files(), List.of(),
                plan.conflicts(), null, 0, 0, "");
    }

    private int indexOf(List<SessionEntry> branch, EntryId checkpointEntryId) {
        for (int index = 0; index < branch.size(); index++) {
            if (branch.get(index).id().equals(checkpointEntryId)) {
                return index;
            }
        }
        return -1;
    }

    private int discardStart(List<SessionEntry> branch, EntryId baseLeafId) {
        if (baseLeafId == null) {
            return 0;
        }
        int index = indexOf(branch, baseLeafId);
        return index < 0 ? 0 : index + 1;
    }

    private String latestTree(List<SessionEntry> branch, int checkpointIndex, String fallback) {
        String latest = fallback;
        for (int index = checkpointIndex + 1; index < branch.size(); index++) {
            Object payload = branch.get(index).payload();
            if (payload instanceof GitChangeSetPayload change) {
                latest = change.treeSha();
            } else if (payload instanceof GitCheckpointPayload next) {
                latest = next.baseTreeSha();
            }
        }
        return latest;
    }

    private int countRuns(List<SessionEntry> discarded) {
        return (int) discarded.stream()
                .filter(entry -> entry.payload() instanceof GitCheckpointPayload).count();
    }

    private GitRestorePlan plan(ChatSession session, String targetTree, String latestTree) {
        try {
            return git.planRestore(session.config().cwd(), targetTree, latestTree);
        } catch (DomainException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new DomainException(DomainErrorCode.GIT_OPERATION_FAILED,
                    "读取还原计划失败: " + message(error));
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

- [ ] **Step 5: Run the tests and confirm they pass**

```bash
cd D:/code/pi-java
JAVA_HOME="C:/Users/R9000P/.jdks/ms-21.0.10" mvn -o -pl pi-java-application -am test \
  -Dtest=SessionRewindServiceTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 6: Commit**

```bash
cd D:/code/pi-java
git add pi-java-application/src/main/java/com/piagent/application/timeline/SessionRewindResult.java \
  pi-java-application/src/main/java/com/piagent/application/timeline/SessionRewindService.java \
  pi-java-application/src/test/java/com/piagent/application/timeline/SessionRewindServiceTest.java
git commit -m "feat(m22): preview and rewind to any checkpoint"
```

---

## Task 7: Expose timeline and rewind over HTTP

**Files:**
- Create: `pi-java-trigger/src/main/java/com/piagent/trigger/http/session/SessionTimelineController.java`
- Create: `pi-java-trigger/src/main/java/com/piagent/trigger/http/session/SessionTimelineResponse.java`
- Create: `pi-java-trigger/src/main/java/com/piagent/trigger/http/session/SessionRewindRequest.java`
- Create: `pi-java-trigger/src/main/java/com/piagent/trigger/http/session/SessionRewindResponse.java`
- Modify: `pi-java-trigger/src/main/java/com/piagent/trigger/http/ApiExceptionHandler.java:79-85`
- Modify: `pi-java-trigger/src/main/java/com/piagent/trigger/http/session/SessionEntryResponse.java:64-66`
- Test: `pi-java-trigger/src/test/java/com/piagent/trigger/http/session/SessionTimelineControllerTest.java`
- Test: `pi-java-trigger/src/test/java/com/piagent/trigger/http/session/SessionEntryResponseTest.java`
- Test: `pi-java-app/src/test/java/com/piagent/app/timeline/DecisionTimelineIntegrationTest.java`

- [ ] **Step 1: Write the failing timeline controller test**

```java
package com.piagent.trigger.http.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.piagent.application.timeline.SessionRewindResult;
import com.piagent.application.timeline.SessionRewindService;
import com.piagent.application.timeline.SessionTimeline;
import com.piagent.application.timeline.SessionTimelineService;
import com.piagent.domain.git.model.FileChange;
import com.piagent.domain.git.model.GitChangeKind;
import com.piagent.domain.git.model.GitConflict;
import com.piagent.domain.plan.model.PlanItem;
import com.piagent.domain.plan.model.PlanItemStatus;
import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.payload.ApprovalPayload;
import com.piagent.domain.session.model.payload.GitChangeSetPayload;
import com.piagent.domain.session.model.payload.VerificationPayload;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.trigger.http.ApiExceptionHandler;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 时间线与回退控制器测试：DTO 映射、预览/冲突响应与状态码。
 */
class SessionTimelineControllerTest {

    private static final SessionId SESSION_ID = SessionId.parse("01TST000000000000000000001");
    private static final EntryId CHECKPOINT = EntryId.parse("01TST000000000000000000002");

    private final ObjectMapper json = new ObjectMapper();
    private SessionTimelineService timelines;
    private SessionRewindService rewinds;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        timelines = mock(SessionTimelineService.class);
        rewinds = mock(SessionRewindService.class);
        mvc = MockMvcBuilders.standaloneSetup(
                        new SessionTimelineController(timelines, rewinds))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void returnsTimelineNodes() throws Exception {
        when(timelines.timeline(SESSION_ID)).thenReturn(timeline());

        MvcResult result = mvc.perform(get("/api/v1/sessions/{id}/timeline", SESSION_ID))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = bodyOf(result);
        assertThat(body.path("available").asBoolean()).isTrue();
        assertThat(body.path("currentCheckpointEntryId").asText())
                .isEqualTo(CHECKPOINT.toString());
        JsonNode node = body.path("nodes").get(0);
        assertThat(node.path("type").asText()).isEqualTo("RUN");
        assertThat(node.path("checkpointEntryId").asText()).isEqualTo(CHECKPOINT.toString());
        assertThat(node.path("current").asBoolean()).isTrue();
        assertThat(node.path("rewindable").asBoolean()).isTrue();
        assertThat(node.path("intent").asText()).isEqualTo("修复登录");
        assertThat(node.path("plan").path("done").asInt()).isEqualTo(1);
        assertThat(node.path("approvals").path("denied").asInt()).isEqualTo(1);
        assertThat(node.path("verification").path("failed").asInt()).isEqualTo(1);
        assertThat(node.path("changes").path("files").asInt()).isEqualTo(1);
        assertThat(node.path("evidence").path("plan").get(0).path("content").asText())
                .isEqualTo("补测试");
        assertThat(node.path("evidence").path("approvals").get(0).path("decision").asText())
                .isEqualTo("DENIED");
    }

    @Test
    void hidesForeignSessionAsNotFound() throws Exception {
        when(timelines.timeline(SESSION_ID)).thenThrow(
                new DomainException(DomainErrorCode.SESSION_NOT_FOUND, "会话不存在"));

        MvcResult result = mvc.perform(get("/api/v1/sessions/{id}/timeline", SESSION_ID))
                .andExpect(status().isNotFound())
                .andReturn();

        assertThat(bodyOf(result).path("code").asText()).isEqualTo("SESSION_NOT_FOUND");
    }

    @Test
    void previewsRewind() throws Exception {
        when(rewinds.rewind(SESSION_ID, CHECKPOINT, true, "换一种修法"))
                .thenReturn(new SessionRewindResult(true, false, true, List.of("a.txt"),
                        List.of(), List.of(), null, 3, 1, "从 14:32 的决策点回退"));

        MvcResult result = mvc.perform(post("/api/v1/sessions/{id}/rewind", SESSION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "checkpointEntryId", CHECKPOINT.toString(),
                                "dryRun", true,
                                "note", "换一种修法"))))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = bodyOf(result);
        assertThat(body.path("dryRun").asBoolean()).isTrue();
        assertThat(body.path("rewound").asBoolean()).isFalse();
        assertThat(body.path("restorable").asBoolean()).isTrue();
        assertThat(body.path("files").get(0).asText()).isEqualTo("a.txt");
        assertThat(body.path("discardedEntries").asInt()).isEqualTo(3);
        assertThat(body.path("discardedRuns").asInt()).isEqualTo(1);
        assertThat(body.path("brief").asText()).contains("回退");
        verify(rewinds).rewind(SESSION_ID, CHECKPOINT, true, "换一种修法");
    }

    @Test
    void returnsConflictsWithoutCounts() throws Exception {
        when(rewinds.rewind(SESSION_ID, CHECKPOINT, false, null))
                .thenReturn(new SessionRewindResult(false, false, false, List.of("a.txt"),
                        List.of(),
                        List.of(new GitConflict("a.txt",
                                "目标之后工作区又发生了变化，为避免覆盖已跳过")),
                        null, 0, 0, ""));

        MvcResult result = mvc.perform(post("/api/v1/sessions/{id}/rewind", SESSION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "checkpointEntryId", CHECKPOINT.toString(),
                                "dryRun", false))))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = bodyOf(result);
        assertThat(body.path("rewound").asBoolean()).isFalse();
        assertThat(body.path("restorable").asBoolean()).isFalse();
        assertThat(body.path("discardedEntries").asInt()).isZero();
        assertThat(body.path("conflicts").get(0).path("path").asText()).isEqualTo("a.txt");
    }

    @Test
    void mapsInvalidBranchToConflict() throws Exception {
        when(rewinds.rewind(SESSION_ID, CHECKPOINT, true, null))
                .thenThrow(new DomainException(DomainErrorCode.INVALID_BRANCH,
                        "目标不是当前分支上的检查点"));

        MvcResult result = mvc.perform(post("/api/v1/sessions/{id}/rewind", SESSION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "checkpointEntryId", CHECKPOINT.toString(),
                                "dryRun", true))))
                .andExpect(status().isConflict())
                .andReturn();

        assertThat(bodyOf(result).path("code").asText()).isEqualTo("INVALID_BRANCH");
    }

    @Test
    void rejectsBlankCheckpointId() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/sessions/{id}/rewind", SESSION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dryRun\":true}"))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertThat(bodyOf(result).path("code").asText()).isEqualTo("INVALID_ARGUMENT");
    }

    private JsonNode bodyOf(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static SessionTimeline timeline() {
        PlanItem item = new PlanItem("补测试", PlanItemStatus.PENDING);
        ApprovalPayload approval = new ApprovalPayload("ap-1", "call-1", "write",
                "DENIED", "不要改这里", "user");
        VerificationPayload verification = new VerificationPayload("run-1", "mvn test", false,
                1, false, false, 1200, "FAIL");
        GitChangeSetPayload change = new GitChangeSetPayload("run-1", 1, null, "tree-0",
                "tree-1",
                List.of(new FileChange("a.txt", GitChangeKind.MODIFIED, 2, 1, false)),
                "diff", false, "fix: 更新 a.txt");
        SessionTimeline.Stats stats = new SessionTimeline.Stats(
                new SessionTimeline.PlanSummary(2, 1),
                new SessionTimeline.ApprovalSummary(1, 1),
                new SessionTimeline.VerificationSummary(1, 1),
                new SessionTimeline.ChangeSummary(1, 1, 2, 1));
        SessionTimeline.RunEvidence run = new SessionTimeline.RunEvidence("修复登录", stats,
                "COMPLETED", 3, 5, List.of(item), List.of(approval),
                List.of(verification), List.of(change));
        SessionTimeline.Node node = new SessionTimeline.Node(SessionTimeline.NodeKind.RUN,
                CHECKPOINT, "run-1", Instant.parse("2026-11-05T10:00:00Z"), true, true,
                run, null);
        return new SessionTimeline(true, CHECKPOINT, List.of(node));
    }
}
```

- [ ] **Step 2: Add the failing branch-summary mapping test**

Create `SessionEntryResponseTest`:

```java
package com.piagent.trigger.http.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.EntryType;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.payload.BranchSummaryPayload;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * entry 响应测试：回溯简报必须把 details 透给前端。
 */
class SessionEntryResponseTest {

    @Test
    void includesRewindDetailsOnBranchSummary() {
        EntryId from = EntryId.parse("01TST000000000000000000003");
        Map<String, Object> details = Map.of("kind", "rewind", "discardedEntries", 3);
        SessionEntry entry = new SessionEntry(EntryId.parse("01TST000000000000000000004"),
                SessionId.parse("01TST000000000000000000001"), 4, null,
                EntryType.BRANCH_SUMMARY, SessionEntry.CURRENT_PAYLOAD_VERSION,
                new BranchSummaryPayload(from, "回溯简报", details), 8, Instant.now());

        SessionEntryResponse response = SessionEntryResponse.from(entry);

        assertThat(response.body().extra()).containsEntry("details", details);
        assertThat(response.body().extra()).containsEntry("summary", "回溯简报");
    }
}
```

- [ ] **Step 3: Run the tests and confirm failure**

```bash
cd D:/code/pi-java
JAVA_HOME="C:/Users/R9000P/.jdks/ms-21.0.10" mvn -o -pl pi-java-trigger -am test \
  -Dtest=SessionTimelineControllerTest,SessionEntryResponseTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: compilation fails because the timeline controller and DTOs do not exist.

- [ ] **Step 4: Create the timeline response DTO**

```java
package com.piagent.trigger.http.session;

import com.piagent.application.timeline.SessionTimeline;
import com.piagent.domain.git.model.FileChange;
import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.payload.ApprovalPayload;
import com.piagent.domain.session.model.payload.GitChangeSetPayload;
import com.piagent.domain.session.model.payload.VerificationPayload;
import java.util.List;

/**
 * 当前分支的决策时间线响应。
 *
 * @param available 当前分支是否存在 Git 检查点
 * @param currentCheckpointEntryId 当前叶子所在 RUN 节点的检查点
 * @param nodes 按分支位置排列的节点
 */
public record SessionTimelineResponse(boolean available, String currentCheckpointEntryId,
        List<NodeResponse> nodes) {

    /**
     * 一个决策点。
     *
     * @param type RUN 或 REWIND
     * @param checkpointEntryId 检查点 entry
     * @param runId run 标识
     * @param at 节点时间
     * @param current 是否包含当前叶子
     * @param rewindable 是否允许回退
     * @param intent 触发这次 run 的用户文本
     * @param plan 计划统计
     * @param approvals 审批统计
     * @param verification 验证统计
     * @param changes 改动统计
     * @param stopReason 停止原因
     * @param turns 轮数
     * @param toolCalls 工具调用数
     * @param brief 回溯简报；RUN 节点为空串
     * @param discardedEntries 被丢弃的 entry 数
     * @param discardedRuns 被丢弃的 run 数
     * @param evidence 可展开的证据
     */
    public record NodeResponse(String type, String checkpointEntryId, String runId, String at,
            boolean current, boolean rewindable, String intent, PlanSummaryView plan,
            ApprovalSummaryView approvals, VerificationSummaryView verification,
            ChangeSummaryView changes, String stopReason, int turns, int toolCalls,
            String brief, int discardedEntries, int discardedRuns, EvidenceView evidence) {
    }

    /** 计划统计。 */
    public record PlanSummaryView(int total, int done) {
    }

    /** 审批统计。 */
    public record ApprovalSummaryView(int total, int denied) {
    }

    /** 验证统计。 */
    public record VerificationSummaryView(int total, int failed) {
    }

    /** 改动统计。 */
    public record ChangeSummaryView(int turns, int files, int additions, int deletions) {
    }

    /**
     * 可展开的证据。
     *
     * @param plan 待办快照
     * @param approvals 审批
     * @param verifications 自动验证
     * @param changes 逐轮改动
     */
    public record EvidenceView(List<PlanItemView> plan, List<ApprovalView> approvals,
            List<VerificationView> verifications, List<ChangeView> changes) {
    }

    /** 待办条目。 */
    public record PlanItemView(String content, String status) {
    }

    /** 审批记录。 */
    public record ApprovalView(String toolName, String decision, String reason) {
    }

    /** 验证记录。 */
    public record VerificationView(String command, boolean passed, Integer exitCode,
            boolean timedOut, String output) {
    }

    /** 一轮改动。 */
    public record ChangeView(int turn, String suggestedCommit, List<FileView> files,
            String diff) {
    }

    /** 改动文件。 */
    public record FileView(String path, String kind, int additions, int deletions,
            boolean binary) {
    }

    /**
     * 从应用层投影生成响应。
     *
     * @param timeline 时间线投影
     * @return 响应
     */
    public static SessionTimelineResponse from(SessionTimeline timeline) {
        return new SessionTimelineResponse(timeline.available(),
                timeline.currentCheckpointEntryId() == null ? null
                        : timeline.currentCheckpointEntryId().toString(),
                timeline.nodes().stream().map(SessionTimelineResponse::node).toList());
    }

    private static NodeResponse node(SessionTimeline.Node node) {
        SessionTimeline.RunEvidence run = node.run();
        SessionTimeline.RewindEvidence rewind = node.rewind();
        SessionTimeline.Stats stats = run == null ? null : run.stats();
        return new NodeResponse(node.kind().name(), id(node.checkpointEntryId()),
                node.runId(), node.at().toString(), node.current(), node.rewindable(),
                run == null ? "" : run.intent(),
                stats == null ? null : new PlanSummaryView(stats.plan().total(),
                        stats.plan().done()),
                stats == null ? null : new ApprovalSummaryView(stats.approvals().total(),
                        stats.approvals().denied()),
                stats == null ? null : new VerificationSummaryView(
                        stats.verification().total(), stats.verification().failed()),
                stats == null ? null : new ChangeSummaryView(stats.changes().turns(),
                        stats.changes().files(), stats.changes().additions(),
                        stats.changes().deletions()),
                run == null ? "" : run.stopReason(),
                run == null ? 0 : run.turns(),
                run == null ? 0 : run.toolCalls(),
                rewind == null ? "" : rewind.brief(),
                rewind == null ? 0 : rewind.discardedEntries(),
                rewind == null ? 0 : rewind.discardedRuns(),
                evidence(run));
    }

    private static EvidenceView evidence(SessionTimeline.RunEvidence run) {
        if (run == null) {
            return null;
        }
        return new EvidenceView(
                run.planItems().stream()
                        .map(item -> new PlanItemView(item.content(), item.status().wireName()))
                        .toList(),
                run.approvals().stream().map(SessionTimelineResponse::approval).toList(),
                run.verifications().stream().map(SessionTimelineResponse::verification).toList(),
                run.changes().stream().map(SessionTimelineResponse::change).toList());
    }

    private static ApprovalView approval(ApprovalPayload payload) {
        return new ApprovalView(payload.toolName(), payload.decision(),
                payload.reason() == null ? "" : payload.reason());
    }

    private static VerificationView verification(VerificationPayload payload) {
        return new VerificationView(payload.command(), payload.passed(), payload.exitCode(),
                payload.timedOut(), payload.output());
    }

    private static ChangeView change(GitChangeSetPayload payload) {
        return new ChangeView(payload.turn(), payload.suggestedCommit(),
                payload.files().stream().map(SessionTimelineResponse::file).toList(),
                payload.diff());
    }

    private static FileView file(FileChange change) {
        return new FileView(change.path(), change.kind().name(), change.additions(),
                change.deletions(), change.binary());
    }

    private static String id(EntryId entryId) {
        return entryId == null ? null : entryId.toString();
    }
}
```

- [ ] **Step 5: Create the rewind request and response**

```java
package com.piagent.trigger.http.session;

import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;

/**
 * 回退请求。
 *
 * @param checkpointEntryId 目标检查点 entry
 * @param dryRun 是否只预览；缺省为 true
 * @param note 可选备注，最多 200 字
 */
public record SessionRewindRequest(String checkpointEntryId, Boolean dryRun, String note) {

    /**
     * 解析检查点标识。
     *
     * @return 检查点 entry
     */
    public EntryId requireCheckpointId() {
        if (checkpointEntryId == null || checkpointEntryId.isBlank()) {
            throw new DomainException(DomainErrorCode.INVALID_ARGUMENT,
                    "checkpointEntryId 不能为空");
        }
        try {
            return EntryId.parse(checkpointEntryId);
        } catch (RuntimeException error) {
            throw new DomainException(DomainErrorCode.INVALID_ARGUMENT,
                    "checkpointEntryId 不是合法的 ULID");
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

```java
package com.piagent.trigger.http.session;

import com.piagent.application.timeline.SessionRewindResult;
import com.piagent.domain.git.model.GitConflict;
import java.util.List;

/**
 * 回退预览或执行结果。
 *
 * @param dryRun 是否只是预览
 * @param rewound 是否已完成回退
 * @param restorable 工作区是否可以安全还原
 * @param files 计划还原的路径
 * @param restored 实际还原的路径
 * @param conflicts 冲突列表
 * @param briefEntryId 新写入的回溯简报 entry
 * @param discardedEntries 被丢弃的 entry 数
 * @param discardedRuns 被丢弃的 run 数
 * @param brief 简报正文
 */
public record SessionRewindResponse(boolean dryRun, boolean rewound, boolean restorable,
        List<String> files, List<String> restored, List<ConflictResponse> conflicts,
        String briefEntryId, int discardedEntries, int discardedRuns, String brief) {

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
     * @param result 回退结果
     * @return 响应
     */
    public static SessionRewindResponse from(SessionRewindResult result) {
        return new SessionRewindResponse(result.dryRun(), result.rewound(),
                result.restorable(), result.files(), result.restored(),
                result.conflicts().stream().map(SessionRewindResponse::conflict).toList(),
                result.briefEntryId() == null ? null : result.briefEntryId().toString(),
                result.discardedEntries(), result.discardedRuns(), result.brief());
    }

    private static ConflictResponse conflict(GitConflict conflict) {
        return new ConflictResponse(conflict.path(), conflict.reason());
    }
}
```

- [ ] **Step 6: Create the controller**

```java
package com.piagent.trigger.http.session;

import com.piagent.application.timeline.SessionRewindService;
import com.piagent.application.timeline.SessionTimelineService;
import com.piagent.domain.session.model.SessionId;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 决策时间线与任意检查点回退控制器。
 *
 * <p>查询只读数据库，不访问磁盘；回退把预览与执行放在同一个接口里，由
 * {@code dryRun} 区分。服务端不信任预览结果，执行时重新计算冲突。</p>
 */
@RestController
@RequestMapping("/api/v1/sessions")
public class SessionTimelineController {

    private final SessionTimelineService timelines;
    private final SessionRewindService rewinds;

    /**
     * 创建控制器。
     *
     * @param timelines 时间线查询用例
     * @param rewinds 回退用例
     */
    public SessionTimelineController(SessionTimelineService timelines,
            SessionRewindService rewinds) {
        this.timelines = timelines;
        this.rewinds = rewinds;
    }

    /**
     * 查询当前分支的决策时间线。
     *
     * @param id 会话 ID
     * @return 时间线
     */
    @GetMapping("/{id}/timeline")
    public SessionTimelineResponse timeline(@PathVariable String id) {
        return SessionTimelineResponse.from(timelines.timeline(SessionId.parse(id)));
    }

    /**
     * 预览或执行一次回退。
     *
     * @param id 会话 ID
     * @param request 回退请求
     * @return 预览或执行结果；冲突时 HTTP 仍为 200
     */
    @PostMapping("/{id}/rewind")
    public SessionRewindResponse rewind(@PathVariable String id,
            @RequestBody SessionRewindRequest request) {
        return SessionRewindResponse.from(rewinds.rewind(SessionId.parse(id),
                request.requireCheckpointId(), request.preview(), request.note()));
    }
}
```

- [ ] **Step 7: Map `INVALID_BRANCH` to 409 and forward brief details**

In `ApiExceptionHandler.statusOf`, add `INVALID_BRANCH` to the conflict case:

```java
            case MODEL_ALREADY_EXISTS, CONCURRENT_MODIFICATION, MODEL_DISABLED,
                    INVALID_STATE_TRANSITION, SESSION_ARCHIVED, RUN_ALREADY_ACTIVE,
                    PLUGIN_ALREADY_EXISTS, PLUGIN_INVALID_STATE, PLUGIN_TOOL_CONFLICT,
                    APPROVAL_INVALID_STATE, GIT_UNAVAILABLE, INVALID_BRANCH,
                    AUTH_ALREADY_INITIALIZED, AUTH_SETUP_REQUIRED, AUTH_TENANT_REQUIRED,
                    TENANT_ALREADY_EXISTS, TENANT_PROTECTED,
                    USER_ALREADY_EXISTS -> HttpStatus.CONFLICT;
```

In `SessionEntryResponse.bodyOf`, replace the branch-summary case with:

```java
            case BranchSummaryPayload payload -> new EntryBody("system", List.of(), null, null,
                    null, extra("summary", payload.summary(), "fromEntryId",
                            payload.fromEntryId().toString(), "details", payload.details()));
```

- [ ] **Step 8: Run the tests and confirm they pass**

```bash
cd D:/code/pi-java
JAVA_HOME="C:/Users/R9000P/.jdks/ms-21.0.10" mvn -o -pl pi-java-trigger -am test \
  -Dtest=SessionTimelineControllerTest,SessionEntryResponseTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 9: Add the app-level integration test**

Create `DecisionTimelineIntegrationTest`:

```java
package com.piagent.app.timeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.piagent.app.PiJavaApplication;
import com.piagent.application.agent.command.SendMessageCommand;
import com.piagent.application.agent.readmodel.RunHandle;
import com.piagent.application.agent.service.AgentLoopService;
import com.piagent.application.session.command.CreateSessionCommand;
import com.piagent.application.session.service.SessionApplicationService;
import com.piagent.application.timeline.SessionRewindResult;
import com.piagent.application.timeline.SessionRewindService;
import com.piagent.application.timeline.SessionTimeline;
import com.piagent.application.timeline.SessionTimelineService;
import com.piagent.domain.agent.AgentRun;
import com.piagent.domain.agent.RunStatus;
import com.piagent.domain.model.ModelEndpoint;
import com.piagent.domain.model.ModelId;
import com.piagent.domain.model.ModelProfile;
import com.piagent.domain.model.port.ModelProfileRepository;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.ThinkingLevel;
import com.piagent.domain.session.model.message.BranchSummaryMessage;
import com.piagent.domain.session.model.payload.BranchSummaryPayload;
import com.piagent.domain.session.port.SessionEntryStore;
import com.piagent.domain.session.port.SessionRepository;
import com.piagent.domain.session.service.SessionContextProjector;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 时间线与回退的应用级集成测试：两次 run → 时间线 → 回退 → 继续运行。
 */
@SpringBootTest(classes = PiJavaApplication.class,
        properties = {"pi.fake.demo-mode=true", "pi.git.enabled=true"})
@ActiveProfiles("test")
class DecisionTimelineIntegrationTest {

    private static final String MODEL_ID = "timeline-test-fake";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    @Autowired
    private AgentLoopService agentLoop;

    @Autowired
    private SessionApplicationService sessions;

    @Autowired
    private SessionTimelineService timelines;

    @Autowired
    private SessionRewindService rewinds;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private SessionEntryStore entryStore;

    @Autowired
    private ModelProfileRepository models;

    @Autowired
    private JdbcTemplate jdbc;

    private Path workspace;

    @BeforeEach
    void setUp() throws IOException {
        jdbc.update("DELETE FROM pi_session_entry");
        jdbc.update("DELETE FROM pi_session");
        jdbc.update("DELETE FROM pi_tool_policy");
        jdbc.update("DELETE FROM pi_model_profile");
        workspace = Path.of("target/timeline-integration-workspace").toAbsolutePath().normalize();
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("notes.md"), "one\n" + System.nanoTime() + "\n");
        git("init", "-q");
        git("add", "notes.md");
        git("-c", "user.name=pi", "-c", "user.email=pi@localhost", "commit", "-qm", "init");
        models.insert(ModelProfile.create(new ModelId(MODEL_ID), "Timeline Fake",
                ModelEndpoint.fake(MODEL_ID)));
    }

    @Test
    void rewindsSecondRunAndContinuesWithBriefInContext() {
        ChatSession session = sessions.createSession(
                new CreateSessionCommand("时间线集成", workspace, MODEL_ID, ThinkingLevel.OFF));
        SessionId sessionId = session.identity().id();
        run(sessionId, "第一次");
        run(sessionId, "第二次");

        SessionTimeline timeline = timelines.timeline(sessionId);
        assertThat(timeline.available()).isTrue();
        assertThat(timeline.nodes()).hasSize(2);
        SessionTimeline.Node target = timeline.nodes().get(1);
        assertThat(target.current()).isTrue();
        assertThat(target.checkpointEntryId()).isNotNull();

        SessionRewindResult result = rewinds.rewind(sessionId, target.checkpointEntryId(),
                false, "换一种修法");
        assertThat(result.rewound()).isTrue();
        assertThat(result.briefEntryId()).isNotNull();

        List<SessionEntry> branch = currentBranch(sessionId);
        SessionEntry brief = branch.stream()
                .filter(entry -> entry.id().equals(result.briefEntryId()))
                .findFirst().orElseThrow();
        assertThat(brief.payload()).isInstanceOfSatisfying(BranchSummaryPayload.class,
                payload -> {
                    assertThat(payload.details().get("kind")).isEqualTo("rewind");
                    assertThat(payload.summary()).contains("换一种修法");
                });

        run(sessionId, "按新思路继续");
        List<SessionEntry> continued = currentBranch(sessionId);
        SessionContextProjector projector = new SessionContextProjector();
        assertThat(projector.project(continued, continued.getLast().id()).messages())
                .hasAtLeastOneElementOfType(BranchSummaryMessage.class);
    }

    private void run(SessionId sessionId, String text) {
        RunHandle handle = agentLoop.start(new SendMessageCommand(sessionId, text));
        AgentRun run = agentLoop.awaitTerminal(handle.runId(), TIMEOUT);
        assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
    }

    private List<SessionEntry> currentBranch(SessionId sessionId) {
        ChatSession session = sessionRepository.findById(sessionId).orElseThrow();
        return entryStore.loadBranch(sessionId, session.leafEntryId());
    }

    private void git(String... args) throws IOException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(workspace.toFile())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        try {
            if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IOException("git 失败: " + output);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("git 被中断", error);
        }
    }
}
```

Run it:

```bash
cd D:/code/pi-java
JAVA_HOME="C:/Users/R9000P/.jdks/ms-21.0.10" mvn -o -pl pi-java-app -am test \
  -Dtest=DecisionTimelineIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 10: Commit**

```bash
cd D:/code/pi-java
git add pi-java-trigger/src/main/java/com/piagent/trigger/http/session/SessionTimelineController.java \
  pi-java-trigger/src/main/java/com/piagent/trigger/http/session/SessionTimelineResponse.java \
  pi-java-trigger/src/main/java/com/piagent/trigger/http/session/SessionRewindRequest.java \
  pi-java-trigger/src/main/java/com/piagent/trigger/http/session/SessionRewindResponse.java \
  pi-java-trigger/src/main/java/com/piagent/trigger/http/ApiExceptionHandler.java \
  pi-java-trigger/src/main/java/com/piagent/trigger/http/session/SessionEntryResponse.java \
  pi-java-trigger/src/test/java/com/piagent/trigger/http/session/SessionTimelineControllerTest.java \
  pi-java-trigger/src/test/java/com/piagent/trigger/http/session/SessionEntryResponseTest.java \
  pi-java-app/src/test/java/com/piagent/app/timeline/DecisionTimelineIntegrationTest.java
git commit -m "feat(m22): expose decision timeline and rewind API"
```

---

## Task 8: Build the Web timeline panel

**Files:**
- Create: `pi-java-app/src/main/resources/static/js/timeline.js`
- Create: `pi-java-app/src/main/resources/static/css/timeline.css`
- Modify: `pi-java-app/src/main/resources/static/index.html`
- Modify: `pi-java-app/src/main/resources/static/js/api.js:255-280`
- Modify: `pi-java-app/src/main/resources/static/js/store.js:60-69`
- Modify: `pi-java-app/src/main/resources/static/js/app.js:11-17,45-56`
- Modify: `pi-java-app/src/main/resources/static/js/session-view.js:5-9,158-162,264-267,290-294`
- Modify: `pi-java-app/src/main/resources/static/js/run.js:8-15,269-270,323-325`
- Modify: `pi-java-app/src/main/resources/static/js/turn-view.js:478-481`

- [ ] **Step 1: Add the two API methods**

In `api.js`, after `undoSession`:

```js
/**
 * 查询当前分支的决策时间线。
 *
 * @param {string} sessionId 会话 ID
 * @returns {Promise<object>} 按分支位置排列的节点
 */
export function sessionTimeline(sessionId) {
    return request(`/api/v1/sessions/${encodeURIComponent(sessionId)}/timeline`);
}

/**
 * 预览或执行回退到任意检查点。
 *
 * @param {string} sessionId 会话 ID
 * @param {{checkpointEntryId: string, dryRun: boolean, note?: string}} payload 回退请求
 * @returns {Promise<object>} 预览或执行结果
 */
export function rewindSession(sessionId, payload) {
    return request(`/api/v1/sessions/${encodeURIComponent(sessionId)}/rewind`,
        jsonInit("POST", payload));
}
```

- [ ] **Step 2: Add the DOM handles**

In `store.js`, after `changesDetail`:

```js
    timelineToggle: document.getElementById("timeline-toggle"),
    timelineSpine: document.getElementById("timeline-spine"),
    timelinePanel: document.getElementById("timeline-panel"),
    timelineClose: document.getElementById("timeline-close"),
    timelineSubtitle: document.getElementById("timeline-subtitle"),
    timelineStatus: document.getElementById("timeline-status"),
    timelineList: document.getElementById("timeline-list"),
    timelinePreview: document.getElementById("timeline-preview"),
    timelinePreviewForm: document.getElementById("timeline-preview-form"),
    timelinePreviewClose: document.getElementById("timeline-preview-close"),
    timelinePreviewKicker: document.getElementById("timeline-preview-kicker"),
    timelinePreviewTitle: document.getElementById("timeline-preview-title"),
    timelinePreviewBody: document.getElementById("timeline-preview-body"),
    timelinePreviewError: document.getElementById("timeline-preview-error"),
    timelineNote: document.getElementById("timeline-note"),
    timelineConfirm: document.getElementById("timeline-confirm"),
    timelineCancel: document.getElementById("timeline-cancel"),
```

- [ ] **Step 3: Add the markup and stylesheet link**

In `index.html`, add after the `messages.css` link:

```html
    <link rel="stylesheet" href="/css/timeline.css">
```

Add the toggle as the first child of `.topbar-actions`:

```html
                <button id="timeline-toggle" class="timeline-entry hidden" type="button"
                        title="决策时间线" aria-label="决策时间线"
                        aria-controls="timeline-panel" aria-expanded="false">
                    <span class="timeline-entry-mark" aria-hidden="true">◷</span>
                    <span class="timeline-entry-label">时间线</span>
                </button>
```

Add the decision spine and panel immediately after `</header>` and before
`<div class="thread">`:

```html
        <nav id="timeline-spine" class="timeline-spine hidden" aria-label="决策刻度"></nav>

        <aside id="timeline-panel" class="timeline-panel" aria-label="决策时间线"
               aria-hidden="true">
            <header class="timeline-head">
                <div>
                    <p class="timeline-kicker">决策脊</p>
                    <h2>决策时间线</h2>
                    <p id="timeline-subtitle" class="timeline-subtitle"></p>
                </div>
                <button id="timeline-close" class="icon-button" type="button"
                        aria-label="关闭时间线">×</button>
            </header>
            <p id="timeline-status" class="timeline-status" aria-live="polite"></p>
            <div id="timeline-list" class="timeline-list"></div>
        </aside>
```

Add the preview dialog and brief template immediately before `<template id="empty-template">`:

```html
<dialog id="timeline-preview" class="timeline-preview" aria-modal="true">
    <form id="timeline-preview-form" class="timeline-preview-shell">
        <header class="timeline-preview-head">
            <div>
                <p id="timeline-preview-kicker" class="timeline-kicker">回退预览</p>
                <h2 id="timeline-preview-title">回到这次运行之前</h2>
            </div>
            <button id="timeline-preview-close" class="icon-button" type="button"
                    aria-label="关闭">×</button>
        </header>
        <div id="timeline-preview-body" class="timeline-preview-body"></div>
        <p id="timeline-preview-error" class="dialog-error hidden"></p>
        <label class="field timeline-note-field">
            <span>备注（可选，会写进回溯简报）</span>
            <textarea id="timeline-note" rows="2" maxlength="200"></textarea>
        </label>
        <footer>
            <button id="timeline-cancel" class="ghost-button" type="button">取消</button>
            <button id="timeline-confirm" class="primary-button" type="submit">确认回退</button>
        </footer>
    </form>
</dialog>

<template id="timeline-brief-template">
    <details class="history-note rewind-note" open>
        <summary>
            <span class="history-note-title">回溯简报</span>
            <span class="history-note-state">从这里继续</span>
        </summary>
        <div class="rewind-note-copy">
            <p class="rewind-note-summary"></p>
            <p class="rewind-note-facts"></p>
        </div>
    </details>
</template>
```

- [ ] **Step 4: Create `timeline.css`**

```css
/* ---------- 决策时间线 ---------- */

.timeline-entry {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    height: 32px;
    padding: 0 11px;
    border: 1px solid var(--line);
    border-radius: 999px;
    background: var(--surface);
    color: var(--ink);
    font: inherit;
    font-size: 12.5px;
    cursor: pointer;
    transition: border-color .16s ease, color .16s ease;
}

.timeline-entry:hover {
    border-color: var(--accent);
    color: var(--accent-strong);
}

.timeline-entry-mark {
    font-size: 13px;
    color: var(--accent-strong);
}

.timeline-panel {
    position: fixed;
    top: 0;
    right: 0;
    bottom: 0;
    z-index: 48;
    display: flex;
    flex-direction: column;
    width: min(380px, 94vw);
    border-left: 1px solid var(--line);
    background: var(--canvas);
    box-shadow: var(--shadow-lg);
    transform: translateX(104%);
    transition: transform .24s ease;
}

.timeline-panel.open {
    transform: none;
}

.timeline-head {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 12px;
    padding: 18px 18px 12px;
    border-bottom: 1px solid var(--line);
}

.timeline-head h2 {
    margin: 0;
    font-size: 17px;
}

.timeline-kicker {
    margin: 0 0 2px;
    font: 600 10.5px/1 var(--mono);
    letter-spacing: .12em;
    text-transform: uppercase;
    color: var(--accent-strong);
}

.timeline-subtitle {
    margin: 4px 0 0;
    color: var(--muted);
    font-size: 12px;
}

.timeline-status {
    margin: 0;
    padding: 10px 18px;
    color: var(--muted);
    font-size: 12px;
    line-height: 1.5;
}

.timeline-status:empty {
    display: none;
}

.timeline-status.is-error {
    color: var(--danger);
}

.timeline-list {
    position: relative;
    flex: 1;
    overflow: auto;
    padding: 6px 16px 28px 34px;
}

.timeline-list::before {
    content: "";
    position: absolute;
    top: 0;
    bottom: 18px;
    left: 22px;
    width: 1px;
    background: var(--line);
}

.timeline-node {
    position: relative;
    margin-bottom: 12px;
    padding: 12px 13px;
    border: 1px solid var(--line);
    border-radius: var(--radius-sm);
    background: var(--surface);
}

.timeline-node::before {
    content: "";
    position: absolute;
    top: 16px;
    left: -17px;
    width: 9px;
    height: 9px;
    border: 2px solid var(--muted);
    border-radius: 50%;
    background: var(--surface);
}

.timeline-node.is-current {
    border-color: var(--accent);
    box-shadow: 0 0 0 1px var(--accent-soft);
}

.timeline-node.is-current::before {
    border-color: var(--accent-strong);
    background: var(--accent-strong);
}

.timeline-node.is-rewind::before {
    border-color: var(--amber);
    background: var(--amber);
}

.timeline-node-head {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 8px;
}

.timeline-copy {
    display: flex;
    flex-direction: column;
    gap: 3px;
    min-width: 0;
}

.timeline-title {
    color: var(--ink);
    font-size: 13px;
    font-weight: 600;
    line-height: 1.45;
    overflow-wrap: anywhere;
}

.timeline-meta {
    color: var(--muted);
    font-size: 11px;
}

.timeline-badge {
    flex: none;
    padding: 1px 7px;
    border-radius: 999px;
    background: var(--accent-soft);
    color: var(--accent-strong);
    font-size: 10.5px;
    font-weight: 600;
}

.timeline-stats {
    display: flex;
    flex-wrap: wrap;
    gap: 5px;
    margin-top: 8px;
}

.timeline-stat {
    padding: 2px 7px;
    border-radius: var(--radius-xs);
    background: var(--surface-soft);
    color: var(--muted);
    font-size: 10.5px;
}

.timeline-stop {
    display: block;
    margin-top: 6px;
    color: var(--muted);
    font-size: 11px;
}

.timeline-evidence {
    margin-top: 9px;
    border-top: 1px solid var(--line);
    padding-top: 8px;
}

.timeline-evidence summary {
    cursor: pointer;
    color: var(--muted);
    font-size: 11.5px;
}

.timeline-evidence-section {
    margin-top: 9px;
}

.timeline-evidence-section > summary {
    cursor: pointer;
    margin-bottom: 4px;
    color: var(--muted);
    font: 600 10.5px/1.3 var(--mono);
    letter-spacing: .08em;
    text-transform: uppercase;
}

.timeline-evidence-list {
    margin: 0;
    padding: 0;
    list-style: none;
}

.timeline-evidence-list li {
    padding: 2px 0;
    color: var(--ink);
    font-size: 12px;
    line-height: 1.5;
    overflow-wrap: anywhere;
}

.timeline-evidence-list li.is-done {
    color: var(--success);
}

.timeline-evidence-list li.is-denied {
    color: var(--danger);
}

.timeline-evidence-list li.is-conflict {
    color: var(--danger);
}

.timeline-mark {
    display: inline-block;
    width: 14px;
    font-weight: 700;
}

.timeline-brief {
    margin: 8px 0 6px;
    color: var(--ink);
    font-size: 12.5px;
    line-height: 1.6;
    overflow-wrap: anywhere;
}

.timeline-rewind {
    width: 100%;
    height: 30px;
    margin-top: 10px;
    border: 1px solid var(--accent);
    border-radius: var(--radius-xs);
    background: var(--accent-soft);
    color: var(--accent-strong);
    font: inherit;
    font-size: 12px;
    font-weight: 600;
    cursor: pointer;
}

.timeline-rewind:hover {
    background: var(--accent);
    color: var(--canvas);
}

.timeline-empty {
    margin: 18px 0;
    color: var(--muted);
    font-size: 12.5px;
    line-height: 1.6;
}

/* ---------- 回退预览 ---------- */

.timeline-preview {
    width: min(520px, 94vw);
    padding: 0;
    border: 0;
    border-radius: var(--radius-md);
    background: var(--surface);
    color: var(--ink);
    box-shadow: var(--shadow-lg);
}

.timeline-preview::backdrop {
    background: rgba(18, 24, 20, .42);
    backdrop-filter: blur(3px);
}

.timeline-preview-shell {
    display: flex;
    flex-direction: column;
    gap: 12px;
    padding: 20px;
}

.timeline-preview-head {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 12px;
}

.timeline-preview-head h2 {
    margin: 0;
    font-size: 17px;
    line-height: 1.4;
}

.timeline-preview-body {
    max-height: 46vh;
    overflow: auto;
}

.timeline-preview-loading {
    margin: 0;
    color: var(--muted);
    font-size: 13px;
}

.timeline-preview-brief {
    margin: 0 0 10px;
    padding: 10px 12px;
    border-left: 3px solid var(--amber);
    border-radius: 0 var(--radius-xs) var(--radius-xs) 0;
    background: var(--surface-soft);
    font-size: 12.5px;
    line-height: 1.65;
    overflow-wrap: anywhere;
}

.timeline-preview-facts {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    margin-bottom: 8px;
}

.timeline-preview-facts span {
    padding: 2px 8px;
    border-radius: 999px;
    background: var(--surface-soft);
    color: var(--muted);
    font-size: 11px;
}

.timeline-preview-files {
    max-height: 140px;
    margin: 0;
    padding-left: 18px;
    overflow: auto;
    font: 11.5px/1.6 var(--mono);
    overflow-wrap: anywhere;
}

.timeline-preview-conflicts {
    margin-top: 10px;
    padding: 10px 12px;
    border: 1px solid var(--danger);
    border-radius: var(--radius-xs);
    color: var(--danger);
    font-size: 12px;
    line-height: 1.55;
}

.timeline-preview-conflicts p {
    margin: 0 0 6px;
}

.timeline-preview-conflicts ul {
    margin: 0;
    padding-left: 18px;
}

.timeline-note-field textarea {
    resize: vertical;
}

.timeline-evidence-diff {
    max-height: 220px;
    margin: 6px 0 0;
    padding: 8px;
    overflow: auto;
    border: 1px solid var(--line);
    border-radius: var(--radius-xs);
    background: var(--surface);
    color: var(--muted);
    font: 11px/1.5 var(--mono);
    white-space: pre;
}

/* ---------- 对话里的回溯简报 ---------- */

.rewind-note {
    border-left: 3px solid var(--amber);
    background: var(--surface);
}

.rewind-note .history-note-title {
    color: var(--amber);
}

.rewind-note-summary {
    margin: 8px 0 4px;
    color: var(--ink);
    font-size: 12.5px;
    line-height: 1.65;
    overflow-wrap: anywhere;
}

.rewind-note-facts {
    margin: 0;
    color: var(--muted);
    font-size: 11.5px;
    line-height: 1.5;
}

/* ---------- 决策脊 ---------- */

.timeline-spine {
    position: fixed;
    top: 96px;
    right: 10px;
    bottom: 128px;
    z-index: 20;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 10px;
    width: 22px;
}

.timeline-tick {
    display: block;
    width: 3px;
    height: 18px;
    padding: 0;
    border: 0;
    border-radius: 999px;
    background: var(--line);
    cursor: pointer;
    transition: height .16s ease, background .16s ease;
}

.timeline-tick:hover,
.timeline-tick:focus-visible {
    height: 26px;
    background: var(--accent-strong);
}

.timeline-tick.is-current {
    background: var(--accent-strong);
}

.timeline-tick.is-rewind {
    width: 9px;
    height: 3px;
    background: var(--amber);
    cursor: default;
}

/* ---------- 窄屏：底部抽屉 ---------- */

@media (max-width: 720px) {
    .timeline-spine {
        display: none;
    }

    .timeline-entry-label {
        display: none;
    }

    .timeline-stats .timeline-stat:nth-child(n + 3) {
        display: none;
    }

    .timeline-panel {
        top: auto;
        left: 0;
        width: 100%;
        max-height: 82vh;
        border-top: 1px solid var(--line);
        border-left: 0;
        border-radius: var(--radius-md) var(--radius-md) 0 0;
        transform: translateY(104%);
    }

    .timeline-panel.open {
        transform: none;
    }
}
```

- [ ] **Step 5: Create `timeline.js`**

```js
/**
 * 决策时间线：查看每次运行前的检查点与证据，并回退到任意检查点。
 *
 * 节点顺序、current 标记与 rewindable 全部由服务端决定，前端不自行推断分支；
 * 所有服务端文本都用 textContent 写入。
 */

import * as api from "./api.js";
import { el, stopReasonLabel } from "./dom.js";
import { dom, state, toast } from "./store.js";

let phase = "closed";
let timeline = null;
let loadedSessionId = null;
let selectedCheckpoint = null;
let previewResult = null;
let reloadHandler = null;
let requestId = 0;
let previewId = 0;

/**
 * 注入回退后的会话重载回调，避免与 session-view 形成循环依赖。
 *
 * @param {() => Promise<void>} handler 重载函数
 */
export function bindReload(handler) {
    reloadHandler = handler;
}

/**
 * 页面初始化：绑定面板、预览与键盘交互。
 */
export function initTimeline() {
    dom.timelineToggle?.addEventListener("click", togglePanel);
    dom.timelineClose?.addEventListener("click", closePanel);
    dom.timelineSpine?.addEventListener("click", onSpineClick);
    dom.timelineList?.addEventListener("click", onListClick);
    dom.timelinePreviewClose?.addEventListener("click", () => closePreview());
    dom.timelineCancel?.addEventListener("click", () => closePreview());
    dom.timelinePreviewForm?.addEventListener("submit", (event) => {
        event.preventDefault();
        confirmRewind();
    });
    dom.timelinePreview?.addEventListener("cancel", (event) => {
        if (phase === "rewinding") {
            event.preventDefault();
        }
    });
    dom.timelinePreview?.addEventListener("close", () => {
        if (phase === "previewing") {
            closePreview(true);
        }
    });
    document.addEventListener("keydown", (event) => {
        if (event.key === "Escape" && !dom.timelinePreview?.open) {
            closePanel();
        }
    });
}

/**
 * 拉取当前会话的时间线；切换会话或 run 结束后调用。
 */
export async function refresh() {
    const sessionId = state.session?.id;
    if (!sessionId) {
        reset();
        return;
    }
    if (phase === "rewinding") {
        return;
    }
    const token = ++requestId;
    phase = "loading";
    showToggle();
    renderStatus("正在读取决策时间线…");
    try {
        const value = await api.sessionTimeline(sessionId);
        if (token !== requestId || state.session?.id !== sessionId) {
            return;
        }
        timeline = value;
        loadedSessionId = sessionId;
        phase = "ready";
        render();
    } catch (error) {
        if (token !== requestId || state.session?.id !== sessionId) {
            return;
        }
        timeline = null;
        loadedSessionId = sessionId;
        phase = "error";
        dom.timelineList.replaceChildren();
        dom.timelineSpine?.replaceChildren();
        dom.timelineSpine?.classList.add("hidden");
        renderStatus(error.message || "读取时间线失败", true);
        showToggle();
    }
}

/**
 * 清空面板；切换或删除会话时调用。
 */
export function reset() {
    requestId++;
    previewId++;
    phase = "closed";
    timeline = null;
    loadedSessionId = null;
    closePanel();
    closePreview(true);
    dom.timelineList?.replaceChildren();
    dom.timelineSpine?.replaceChildren();
    dom.timelineSpine?.classList.add("hidden");
    renderStatus("");
    dom.timelineToggle?.classList.add("hidden");
}

function togglePanel() {
    if (dom.timelinePanel?.classList.contains("open")) {
        closePanel();
        return;
    }
    dom.timelinePanel?.classList.add("open");
    dom.timelinePanel?.setAttribute("aria-hidden", "false");
    dom.timelineToggle?.setAttribute("aria-expanded", "true");
    if (phase === "error" || loadedSessionId !== state.session?.id) {
        refresh();
    } else if (timeline) {
        render();
    }
}

function closePanel() {
    dom.timelinePanel?.classList.remove("open");
    dom.timelinePanel?.setAttribute("aria-hidden", "true");
    dom.timelineToggle?.setAttribute("aria-expanded", "false");
}

function showToggle() {
    dom.timelineToggle?.classList.remove("hidden");
}

function render() {
    const nodes = Array.isArray(timeline?.nodes) ? timeline.nodes : [];
    dom.timelineSubtitle.textContent = nodes.length
        ? `${nodes.length} 个决策点${timeline.available ? "" : " · 无 Git 检查点"}`
        : "还没有可回看的运行";
    dom.timelineList.replaceChildren();
    renderSpine(nodes);
    if (!nodes.length) {
        dom.timelineList.append(el("p", "timeline-empty",
            "还没有决策点，发送第一条消息后出现。"));
        renderStatus("");
        return;
    }
    for (const node of nodes) {
        dom.timelineList.append(renderNode(node));
    }
    const canRewind = nodes.some((node) => node.rewindable);
    renderStatus(timelineHint(canRewind));
}

function timelineHint(canRewind) {
    const status = state.session?.status;
    if (status === "ARCHIVED") {
        return "会话已归档，决策时间线只读。";
    }
    if (state.streaming || status === "RUNNING" || status === "WAITING_APPROVAL") {
        return "等待当前运行结束，之后可以回退。";
    }
    if (!timeline.available) {
        return "当前工作区没有 Git 检查点，只能查看运行证据。";
    }
    return canRewind ? "选一个检查点，先预览再回退。"
        : "当前状态下不能回退，只能查看证据。";
}

function line(className, symbol, text) {
    const row = el("li", className);
    const marker = el("span", "timeline-mark", symbol);
    marker.setAttribute("aria-hidden", "true");
    row.append(marker, document.createTextNode(text));
    return row;
}

function evidenceSection(title) {
    const section = document.createElement("details");
    section.className = "timeline-evidence-section";
    section.open = window.matchMedia("(min-width: 721px)").matches;
    section.append(el("summary", "", title));
    return section;
}

function renderNode(node) {
    const card = el("article", `timeline-node is-${String(node.type).toLowerCase()}`
        + (node.current ? " is-current" : ""));
    if (node.checkpointEntryId) {
        card.dataset.node = node.checkpointEntryId;
    }
    const head = el("div", "timeline-node-head");
    const copy = el("div", "timeline-copy");
    copy.append(el("span", "timeline-title", nodeTitle(node)),
        el("span", "timeline-meta", formatTime(node.at)));
    head.append(copy);
    if (node.current) {
        head.append(el("span", "timeline-badge", "现在"));
    }
    card.append(head);
    if (node.type === "RUN") {
        card.append(statsRow(node));
        if (node.stopReason) {
            const detail = Number(node.turns) > 0
                ? ` · ${node.turns} 轮 · ${Number(node.toolCalls) || 0} 次工具调用`
                : "";
            card.append(el("span", "timeline-stop",
                `结束：${stopReasonLabel(node.stopReason)}${detail}`));
        }
        const evidence = evidenceDetails(node);
        if (evidence) {
            card.append(evidence);
        }
    } else {
        card.append(el("p", "timeline-brief", node.brief || "（无简报）"),
            el("span", "timeline-meta",
                `丢弃 ${node.discardedEntries || 0} 条记录 · `
                + `${node.discardedRuns || 0} 次运行`));
    }
    if (node.rewindable && node.checkpointEntryId) {
        const button = el("button", "timeline-rewind", "回到这次运行之前");
        button.type = "button";
        button.dataset.checkpoint = node.checkpointEntryId;
        card.append(button);
    }
    return card;
}

function renderSpine(nodes) {
    if (!dom.timelineSpine) {
        return;
    }
    dom.timelineSpine.replaceChildren();
    dom.timelineSpine.classList.toggle("hidden", !nodes.length);
    for (const node of nodes) {
        if (node.type === "REWIND") {
            const mark = el("span", "timeline-tick is-rewind");
            mark.title = nodeTitle(node);
            dom.timelineSpine.append(mark);
            continue;
        }
        if (!node.checkpointEntryId) {
            continue;
        }
        const tick = el("button", "timeline-tick" + (node.current ? " is-current" : ""));
        tick.type = "button";
        tick.dataset.spine = node.checkpointEntryId;
        tick.title = `${formatTime(node.at)} · ${shorten(nodeTitle(node), 28)}`;
        tick.setAttribute("aria-label", tick.title);
        dom.timelineSpine.append(tick);
    }
}

function onSpineClick(event) {
    const tick = event.target.closest("button[data-spine]");
    if (!tick) {
        return;
    }
    if (!dom.timelinePanel?.classList.contains("open")) {
        togglePanel();
    }
    const cards = Array.from(dom.timelineList?.querySelectorAll("[data-node]") || []);
    const target = cards.find((card) => card.dataset.node === tick.dataset.spine);
    target?.scrollIntoView({ block: "center", behavior: "smooth" });
}

function nodeTitle(node) {
    if (node.type === "REWIND") {
        return `回到 ${formatTime(node.at)} 之前`;
    }
    return node.intent || "（没有文字意图）";
}

function statsRow(node) {
    const row = el("div", "timeline-stats");
    const plan = node.plan || {};
    if (Number(plan.total) > 0) {
        row.append(el("span", "timeline-stat",
            `计划 ${Number(plan.done || 0)}/${Number(plan.total)}`));
    }
    const approvals = node.approvals || {};
    if (Number(approvals.total) > 0) {
        row.append(el("span", "timeline-stat",
            `审批 ${Number(approvals.total)} · 拒绝 ${Number(approvals.denied || 0)}`));
    }
    const verification = node.verification || {};
    if (Number(verification.total) > 0) {
        row.append(el("span", "timeline-stat",
            `验证 ${Number(verification.failed || 0)} 失败`));
    }
    const changes = node.changes || {};
    if (Number(changes.turns) > 0) {
        row.append(el("span", "timeline-stat",
            `改动 ${Number(changes.turns)} 轮 · ${Number(changes.files || 0)} 文件`));
    }
    return row;
}

function evidenceDetails(node) {
    const evidence = node.evidence || {};
    const sections = [];
    const planItems = Array.isArray(evidence.plan) ? evidence.plan : [];
    if (planItems.length) {
        const section = evidenceSection("计划");
        const list = el("ul", "timeline-evidence-list");
        for (const item of planItems) {
            const done = item.status === "done";
            list.append(line(done ? "is-done" : "", done ? "✓" : "○",
                `${planStatus(item.status)} · ${item.content}`));
        }
        section.append(list);
        sections.push(section);
    }
    const approvals = Array.isArray(evidence.approvals) ? evidence.approvals : [];
    if (approvals.length) {
        const section = evidenceSection("审批");
        const list = el("ul", "timeline-evidence-list");
        for (const approval of approvals) {
            const denied = approval.decision === "DENIED";
            list.append(line(denied ? "is-denied" : "is-done", denied ? "✕" : "✓",
                `${approval.toolName} · ${denied ? "已拒绝" : "已批准"}`
                + (approval.reason ? ` · ${approval.reason}` : "")));
        }
        section.append(list);
        sections.push(section);
    }
    const verifications = Array.isArray(evidence.verifications) ? evidence.verifications : [];
    if (verifications.length) {
        const section = evidenceSection("验证");
        const list = el("ul", "timeline-evidence-list");
        for (const verification of verifications) {
            const exit = verification.exitCode === null || verification.exitCode === undefined
                ? "" : ` · exit ${verification.exitCode}`;
            const output = String(verification.output || "")
                .replace(/\s+/g, " ").trim();
            list.append(line(verification.passed ? "is-done" : "is-denied",
                verification.passed ? "✓" : "✕",
                `${verification.command} · ${verification.passed ? "通过" : "失败"}${exit}`
                + (output ? ` · ${shorten(output, 100)}` : "")));
        }
        section.append(list);
        sections.push(section);
    }
    const changes = Array.isArray(evidence.changes) ? evidence.changes : [];
    if (changes.length) {
        const section = evidenceSection("改动");
        const list = el("ul", "timeline-evidence-list");
        for (const change of changes) {
            const files = Array.isArray(change.files) ? change.files : [];
            const item = el("li", "");
            item.append(el("span", "", `第 ${change.turn} 轮 · ${files.length} 个文件`
                + (change.suggestedCommit ? ` · ${change.suggestedCommit}` : "")));
            const paths = el("ul", "timeline-evidence-list");
            for (const file of files) {
                const size = file.binary
                    ? " · 二进制"
                    : ` · +${file.additions} −${file.deletions}`;
                paths.append(el("li", "", `${file.path} · ${changeKindLabel(file.kind)}${size}`));
            }
            item.append(paths);
            if (change.diff) {
                const diff = document.createElement("pre");
                diff.className = "timeline-evidence-diff";
                diff.textContent = change.diff;
                item.append(diff);
            }
            list.append(item);
        }
        section.append(list);
        sections.push(section);
    }
    if (!sections.length) {
        return null;
    }
    const details = document.createElement("details");
    details.className = "timeline-evidence";
    details.append(el("summary", "", "查看证据"));
    for (const section of sections) {
        details.append(section);
    }
    return details;
}

function planStatus(status) {
    switch (status) {
        case "done":
            return "完成";
        case "in_progress":
            return "进行中";
        default:
            return "待办";
    }
}

function changeKindLabel(kind) {
    switch (kind) {
        case "ADDED":
            return "新增";
        case "DELETED":
            return "删除";
        default:
            return "修改";
    }
}

function onListClick(event) {
    if (!(event.target instanceof Element)) {
        return;
    }
    const button = event.target.closest("button[data-checkpoint]");
    if (button) {
        preview(button.dataset.checkpoint);
    }
}

function nodeOf(checkpointEntryId) {
    return (timeline?.nodes || []).find((node) =>
        node.checkpointEntryId === checkpointEntryId) || null;
}

async function preview(checkpointEntryId) {
    const sessionId = state.session?.id;
    if (!sessionId || !checkpointEntryId
            || (phase !== "ready" && phase !== "previewing")) {
        return;
    }
    const token = ++previewId;
    selectedCheckpoint = checkpointEntryId;
    previewResult = null;
    phase = "previewing";
    openPreview();
    dom.timelinePreviewTitle.textContent = "回到这次运行之前";
    dom.timelinePreviewKicker.textContent = "回退预览";
    dom.timelinePreviewBody.replaceChildren(el("p", "timeline-preview-loading",
        "正在计算要还原的文件…"));
    setPreviewError("");
    dom.timelineNote.value = "";
    dom.timelineConfirm.disabled = true;
    try {
        const result = await api.rewindSession(sessionId, {
            checkpointEntryId,
            dryRun: true
        });
        if (token !== previewId || state.session?.id !== sessionId) {
            return;
        }
        previewResult = result;
        phase = "previewing";
        renderPreview(result);
    } catch (error) {
        if (token !== previewId) {
            return;
        }
        phase = "ready";
        closePreview(true);
        toast(error.message || "无法预览回退", true);
    }
}

function openPreview() {
    if (!dom.timelinePreview.open) {
        dom.timelinePreview.showModal();
    }
}

function renderPreview(result) {
    const body = dom.timelinePreviewBody;
    body.replaceChildren();
    const node = nodeOf(selectedCheckpoint);
    dom.timelinePreviewTitle.textContent = node?.intent
        ? `回到「${shorten(node.intent, 48)}」之前`
        : "回到这次运行之前";
    dom.timelinePreviewKicker.textContent = result.restorable ? "回退预览" : "存在冲突";
    if (result.brief) {
        body.append(el("p", "timeline-preview-brief", result.brief));
    }
    const facts = el("div", "timeline-preview-facts");
    facts.append(el("span", "", `丢弃 ${result.discardedEntries || 0} 条记录`),
        el("span", "", `丢弃 ${result.discardedRuns || 0} 次运行`),
        el("span", "", `还原 ${(result.files || []).length} 个文件`));
    body.append(facts);
    const files = Array.isArray(result.files) ? result.files : [];
    if (files.length) {
        const list = el("ul", "timeline-preview-files");
        for (const path of files) {
            list.append(el("li", "", path));
        }
        body.append(list);
    }
    const conflicts = Array.isArray(result.conflicts) ? result.conflicts : [];
    if (conflicts.length) {
        const box = el("div", "timeline-preview-conflicts");
        box.append(el("p", "", "这些文件在目标之后又被修改，回退被整体放弃："));
        const list = el("ul", "");
        for (const conflict of conflicts) {
            list.append(line("is-conflict", "!", `${conflict.path} · ${conflict.reason}`));
        }
        box.append(list);
        body.append(box);
    }
    dom.timelineConfirm.disabled = !result.restorable;
    dom.timelineConfirm.textContent = result.restorable ? "确认回退" : "无法回退";
    setPreviewError("");
}

async function confirmRewind() {
    const sessionId = state.session?.id;
    if (!sessionId || !selectedCheckpoint || phase !== "previewing"
            || !previewResult?.restorable) {
        return;
    }
    const note = String(dom.timelineNote?.value || "").trim();
    phase = "rewinding";
    dom.timelineConfirm.disabled = true;
    dom.timelineCancel.disabled = true;
    dom.timelinePreviewKicker.textContent = "正在回退";
    dom.timelinePreviewBody.replaceChildren(el("p", "timeline-preview-loading",
        "正在还原文件并把对话退回检查点…"));
    setPreviewError("");
    try {
        const result = await api.rewindSession(sessionId, {
            checkpointEntryId: selectedCheckpoint,
            dryRun: false,
            note
        });
        if (!result.rewound) {
            previewResult = result;
            phase = "previewing";
            renderPreview(result);
            toast(result.conflicts?.length
                ? "有文件在目标之后被修改，未做任何改动"
                : "回退未执行", true);
            return;
        }
        closePreview(true);
        phase = "ready";
        toast(`已回退，还原 ${(result.restored || []).length} 个文件`);
        await reloadHandler?.();
        dom.input?.focus();
    } catch (error) {
        phase = "previewing";
        setPreviewError(error.message || "回退失败");
    } finally {
        dom.timelineCancel.disabled = false;
        if (phase === "previewing") {
            dom.timelineConfirm.disabled = !previewResult?.restorable;
        }
    }
}

function closePreview(force = false) {
    if (phase === "rewinding" && !force) {
        return;
    }
    if (dom.timelinePreview?.open) {
        dom.timelinePreview.close();
    }
    dom.timelinePreviewBody?.replaceChildren();
    setPreviewError("");
    if (dom.timelineNote) {
        dom.timelineNote.value = "";
    }
    if (dom.timelineConfirm) {
        dom.timelineConfirm.textContent = "确认回退";
        dom.timelineConfirm.disabled = true;
    }
    dom.timelinePreviewKicker.textContent = "回退预览";
    selectedCheckpoint = null;
    previewResult = null;
    if (phase !== "closed") {
        phase = "ready";
    }
}

function setPreviewError(message) {
    if (!dom.timelinePreviewError) {
        return;
    }
    dom.timelinePreviewError.textContent = message || "";
    dom.timelinePreviewError.classList.toggle("hidden", !message);
}

function renderStatus(message, isError = false) {
    if (!dom.timelineStatus) {
        return;
    }
    dom.timelineStatus.textContent = message || "";
    dom.timelineStatus.classList.toggle("is-error", Boolean(isError));
}

function formatTime(value) {
    if (!value) {
        return "";
    }
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) {
        return "";
    }
    return date.toLocaleString("zh-CN", {
        month: "2-digit",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit"
    });
}

function shorten(text, limit) {
    const value = String(text || "");
    return value.length <= limit ? value : `${value.slice(0, limit - 1)}…`;
}
```

- [ ] **Step 6: Wire the module into the app**

In `app.js`, import after `plan`:

```js
import * as timeline from "./timeline.js";
```

In `initShell`, after `plan.initPlan()`:

```js
    timeline.initTimeline();
    timeline.bindReload(reloadSession);
```

In `session-view.js`, import after `plan`:

```js
import * as timeline from "./timeline.js";
```

Add `timeline.refresh();` immediately after `plan.refresh();` in both `openSession` and
`reloadSession`. Add `timeline.reset();` immediately after `plan.reset();` in
`clearSession`.

In `run.js`, import after `plan`:

```js
import * as timeline from "./timeline.js";
```

Add `timeline.refresh();` immediately after `changes.refresh();` in the
`workspace.changed` case and in `finishRun`.

- [ ] **Step 7: Render the rewind brief as a card**

In `turn-view.js`, replace the `BRANCH_SUMMARY` branch with:

```js
    if (entry.type === "BRANCH_SUMMARY") {
        if (entry.body?.extra?.details?.kind === "rewind") {
            return rewindNote(entry);
        }
        const from = entry.body?.extra?.fromEntryId ? String(entry.body.extra.fromEntryId).slice(0, 8) : "—";
        return collapsibleNote(entry, "branch-note", `分支摘要，起点 ${from}`);
    }
```

Add after `collapsibleNote`:

```js
function rewindNote(entry) {
    const template = document.getElementById("timeline-brief-template");
    if (!template) {
        return collapsibleNote(entry, "branch-note", "回溯简报");
    }
    const node = template.content.firstElementChild.cloneNode(true);
    const extra = entry.body?.extra || {};
    const details = extra.details || {};
    node.querySelector(".rewind-note-summary").textContent = extra.summary || "回溯简报";
    const facts = [];
    if (Number(details.discardedEntries) > 0) {
        facts.push(`丢弃 ${details.discardedEntries} 条记录`);
    }
    if (Number(details.discardedRuns) > 0) {
        facts.push(`${details.discardedRuns} 次运行`);
    }
    if (Number(details.restoredFiles) > 0) {
        facts.push(`还原 ${details.restoredFiles} 个文件`);
    }
    if (Number(details.deniedApprovals) > 0) {
        facts.push(`${details.deniedApprovals} 次审批被拒`);
    }
    if (Number(details.failedVerifications) > 0) {
        facts.push(`${details.failedVerifications} 次验证失败`);
    }
    if (Number(details.unfinishedPlanItems) > 0) {
        facts.push(`${details.unfinishedPlanItems} 项待办未完成`);
    }
    node.querySelector(".rewind-note-facts").textContent = facts.join(" · ");
    return node;
}
```

- [ ] **Step 8: Verify with the browser**

Build and start an isolated instance; do not touch the user's 8080 process:

```bash
cd D:/code/pi-java
JAVA_HOME="C:/Users/R9000P/.jdks/ms-21.0.10" mvn -o -q -DskipTests package
PI_DATA_DIR="D:/code/pi-java/target/m22-data" \
  PI_VERIFY_COMMAND="exit 1" \
  JAVA_HOME="C:/Users/R9000P/.jdks/ms-21.0.10" \
  "C:/Users/R9000P/.jdks/ms-21.0.10/bin/java" -jar pi-java-app/target/pi-java-app-0.2.0.jar \
  serve --port 8081
```

Prepare a throwaway Git workspace:

```bash
mkdir -p D:/code/pi-java/target/m22-demo
cd D:/code/pi-java/target/m22-demo
git init -q
printf 'one\n' > notes.md
git add notes.md
git -c user.name=pi -c user.email=pi@localhost commit -qm init
```

Then use the browser tools:

1. Open `http://127.0.0.1:8081`, initialize the admin account, create a session with
   cwd `D:/code/pi-java/target/m22-demo` and the Fake model.
2. Send `/write notes.md` twice with different contents, approving each write, then send
   one message that triggers a denied approval. Every run also records a failed automatic
   verification (`PI_VERIFY_COMMAND="exit 1"`), so the third node has a failure to inspect.
3. Confirm the decision spine on the right edge has one tick per RUN node and marks the
   current tick; click the first tick and confirm the panel opens and scrolls to that
   card. Confirm the spine is hidden at 390px.
4. Click “时间线”; confirm three RUN nodes, the latest is current, each shows intent and
   stats; expand “查看证据” and confirm the denied approval and failed verification carry
   both a glyph and text, not color alone.
5. Click “回到这次运行之前” on the second node; confirm the preview lists files, counts and
   the brief; cancel once, then reopen and confirm.
6. Confirm the conversation returns to before the second run, a “回溯简报” card appears,
   the file content matches the checkpoint after the first run, and the next message
   includes the brief without repeating the denied tool.
7. Switch to a 390px viewport; confirm the panel becomes a bottom drawer and Esc closes it.
8. Check the browser console for errors and confirm no `innerHTML` usage was added
   (`grep -R "innerHTML" pi-java-app/src/main/resources/static/js` returns no hits).

- [ ] **Step 9: Commit**

```bash
cd D:/code/pi-java
git add pi-java-app/src/main/resources/static/js/timeline.js \
  pi-java-app/src/main/resources/static/css/timeline.css \
  pi-java-app/src/main/resources/static/index.html \
  pi-java-app/src/main/resources/static/js/api.js \
  pi-java-app/src/main/resources/static/js/store.js \
  pi-java-app/src/main/resources/static/js/app.js \
  pi-java-app/src/main/resources/static/js/session-view.js \
  pi-java-app/src/main/resources/static/js/run.js \
  pi-java-app/src/main/resources/static/js/turn-view.js
git commit -m "feat(m22): add revisitable decision timeline to Web UI"
```

---

## Task 9: Document, verify, and record M22

**Files:**
- Modify: `docs/12-git.md`
- Modify: `docs/adr/0005-git-checkpoints.md`
- Modify: `docs/05-api-and-cli.md`
- Modify: `docs/11-interface-design.md`
- Create: `docs/milestones/M22.md`

- [ ] **Step 1: Update `docs/12-git.md`**

Change the status block at the top to:

```markdown
> 状态：M22 已实现（2026-11-05）。Git 是**可选增强**：工作区不是仓库、机器没装
> git 或显式关闭时，Agent Loop 照常运行，只是没有改动面板与撤销能力。M22 起每次
> 运行前的检查点会被内部 ref 固定，并支持回到任意一次运行之前。
```

Add one line to the configuration block in §2:

```yaml
pi:
  git:
    enabled: true          # 缺省 true；关闭后使用空实现
    timeout-seconds: 10    # 单条 git 命令超时
    max-diff-chars: 60000  # 单个 turn diff 保留上限，超出截断
    pin-checkpoints: true  # M22：用内部 ref 固定检查点，避免被 gc 回收
```

Append the following bullet to the list under the block:

```markdown
- `pin-checkpoints=true` 时，每次运行开始后用
  `refs/pi/checkpoints/<sessionId>/<runId>` 指向检查点树对象，保证后续可回溯；
  固定失败只记 WARNING，不影响运行。
```

After §6.2, append §6.3 and §6.4:

````markdown
### 6.3 决策时间线与任意检查点回溯（M22）

```http
GET  /api/v1/sessions/{id}/timeline
POST /api/v1/sessions/{id}/rewind
```

时间线按运行分组，节点顺序与 `seq` 一致。每个节点包含：

- `type`：`RUN` 或 `REWIND`；
- `checkpointEntryId`：该次运行开始前的检查点 entry；
- 摘要：用户意图、计划进度、审批结论、验证结果、改动文件数；
- `evidence`：本次运行的待办、审批、验证与逐轮改动，供 Web 展开证据。

回溯请求体：

```json
{"checkpointEntryId": "01JD...", "dryRun": true, "note": "换一种修法"}
```

`checkpointEntryId` 是时间线节点的 `checkpointEntryId`，不是运行 ID；`note`
可选，最多 200 字。`dryRun` 缺省为 true，只做预览，不写任何东西。响应：

```json
{
  "dryRun": false,
  "rewound": true,
  "restorable": true,
  "files": ["notes.md", "src/App.java"],
  "restored": ["notes.md", "src/App.java"],
  "conflicts": [],
  "briefEntryId": "01JE...",
  "discardedEntries": 14,
  "discardedRuns": 1,
  "brief": "第 2 轮运行（修改 notes.md）已被撤销，回到第 1 轮运行之前的会话与文件。"
}
```

语义：

- 只有空闲会话可回溯；归档会话返回 409 `SESSION_ARCHIVED`，运行中返回
  409 `INVALID_STATE_TRANSITION`；
- `checkpointEntryId` 不在当前分支上返回 409 `INVALID_BRANCH`；
- 先做冲突预检：只要有文件在目标检查点之后又被修改，整体放弃，返回 HTTP 200、
  `rewound=false`，`conflicts` 带路径与原因，工作区与对话都不变；
- 成功时先还原文件，再截断会话：`baseLeafId` 为空表示回到第一次运行之前，
  清空该会话日志；否则把叶子节点移回 `baseLeafId`；
- 截断后追加一条 `BRANCH_SUMMARY`（`details.kind = "rewind"`）作为“回溯简报”，
  下一次请求会把它作为上下文的一部分；
- 响应里的 `restored` 是实际写入的文件，`files` 是计划还原的文件；`discarded`
  统计被截断的 entry 与运行次数；
- 不移动 HEAD、不修改分支、不自动提交。

### 6.4 检查点固定

`pin-checkpoints=true` 时，运行开始后用 `git commit-tree` 生成内部提交，
`git update-ref` 写入 `refs/pi/checkpoints/<sessionId>/<runId>`：

- 提交只用于让树对象可达，`commit-tree` 的父为空，不进入任何分支；
- 写入时显式提供 `GIT_AUTHOR_NAME`/`GIT_AUTHOR_EMAIL` 等身份变量，避免依赖
  用户 git 配置；时间戳固定，重复固定同一检查点结果稳定；
- 删除会话时同时删除该会话下的所有 `refs/pi/checkpoints/<sessionId>/`；
- 固定是尽力而为：失败只记 WARNING，不阻断运行，也不影响后续回溯（只是检查点
  可能在 `git gc --prune=now` 后失效）。
````

Replace the bullet `- 只能撤销最近一次 run，不能回退到更早的检查点或单轮中间状态；`
in §10 with:

```markdown
- 回溯以运行开始前的检查点为单位，不能回到单轮中间状态，也不能只撤销某一轮工具
  调用；
- 回溯会丢弃目标检查点之后的对话与文件改动，且不可再通过时间线恢复；
- 内部检查点 ref 只在该会话删除时清理，长期保留会让对象库持续增长。
```

Append `M22` to the related-documents list:

```markdown
- [M22 里程碑记录](milestones/M22.md)：决策时间线与任意检查点回溯
```

- [ ] **Step 2: Update `docs/adr/0005-git-checkpoints.md`**

Replace `- 树对象没有任何 ref 指向，\`git gc --prune=now\` 后旧检查点不可还原；`
with:

```markdown
- 默认情况下树对象没有 ref 指向；M22 起可用内部 ref
  `refs/pi/checkpoints/<sessionId>/<runId>` 固定，未固定或固定失败的检查点仍会
  在 `git gc --prune=now` 后不可还原；
```

Replace `- 只能撤销最近一次 run，不支持任意检查点；` with:

```markdown
- M13 只能撤销最近一次 run；M22 增加了任意检查点回溯，但回溯仍以运行开始前的
  检查点为单位，不能回到单轮中间状态；
```

Add at the end of `## 边界`:

```markdown
- 内部 ref 只用于防止对象被回收，不合并、不推送、不展示为分支；
- 删除会话时清理对应 ref，不做全局 gc 或 prune。
```

Append a new section before `## 替代方案`:

````markdown
## 2026-11-05 增补：内部检查点 ref

M13 的树对象没有引用，机器执行 `git gc --prune=now` 后旧检查点会消失。M22 增加
`refs/pi/checkpoints/<sessionId>/<runId>`：用无父 `commit-tree` 包一层树对象，
再 `update-ref` 写入。选择内部 ref 而不是 `refs/heads/` 或 tag，是为了满足：

- 不出现在 `git branch`、`git tag` 与推送默认 refspec 中；
- 仍受 Git 可达性保护，普通 gc 不会回收；
- 生命周期与会话绑定，删除会话即可一次清干净。

代价是每轮多两次 git 调用，且长期使用会积累对象；这是“可回溯”换来的成本。

需要提前回收时，按会话手动删除：

```bash
git for-each-ref --format='%(refname)' refs/pi/checkpoints/<sessionId>/ \
  | xargs -r -n1 git update-ref -d
```

删除后未被其他 ref 引用的树对象会在下一次 `git gc` 时回收。
````

- [ ] **Step 3: Update `docs/05-api-and-cli.md`**

Renumber the two sections after the insertion point:

```markdown
### 2.11 计划与自动验证（M14）
```

becomes

```markdown
### 2.12 计划与自动验证（M14）
```

and

```markdown
### 2.12 图片附件（M16/M17）
```

becomes

```markdown
### 2.13 图片附件（M16/M17）
```

Then insert the new section between the end of §2.10 and the new §2.12:

````markdown
### 2.11 决策时间线与任意检查点回溯（M22）

```http
GET  /api/v1/sessions/{id}/timeline
POST /api/v1/sessions/{id}/rewind
```

时间线响应：

```json
{
  "available": true,
  "currentCheckpointEntryId": "01JU...",
  "nodes": [
    {
      "type": "RUN",
      "checkpointEntryId": "01JU...",
      "runId": "01JV...",
      "at": "2026-11-05T10:00:00Z",
      "current": false,
      "rewindable": true,
      "intent": "把 notes.md 改成三段",
      "plan": {"total": 3, "done": 3},
      "approvals": {"total": 1, "denied": 0},
      "verification": {"total": 1, "failed": 0},
      "changes": {"turns": 1, "files": 1, "additions": 4, "deletions": 0},
      "stopReason": "COMPLETED",
      "turns": 3,
      "toolCalls": 5,
      "brief": "",
      "discardedEntries": 0,
      "discardedRuns": 0,
      "evidence": {
        "plan": [{"content": "读 notes.md", "status": "done"}],
        "approvals": [{"toolName": "write", "decision": "APPROVED", "reason": ""}],
        "verifications": [{"command": "mvn -o test", "passed": true, "exitCode": 0,
                           "timedOut": false, "output": "BUILD SUCCESS"}],
        "changes": [{"turn": 1, "suggestedCommit": "docs: 更新 notes.md", "files": [],
                     "diff": ""}]
      }
    },
    {
      "type": "REWIND",
      "checkpointEntryId": null,
      "runId": "01JY...",
      "at": "2026-11-05T10:05:00Z",
      "current": true,
      "rewindable": false,
      "intent": "",
      "plan": {"total": 0, "done": 0},
      "approvals": {"total": 0, "denied": 0},
      "verification": {"total": 0, "failed": 0},
      "changes": {"turns": 0, "files": 0, "additions": 0, "deletions": 0},
      "stopReason": "",
      "turns": 0,
      "toolCalls": 0,
      "brief": "第 1 轮运行已被撤销，回到它开始之前。",
      "discardedEntries": 9,
      "discardedRuns": 1,
      "evidence": null
    }
  ]
}
```

- `nodes` 只包含当前分支，按分支位置排列；`current=true` 至多一个；
- `REWIND` 节点没有可回溯检查点，`checkpointEntryId=null`、`rewindable=false`；
- `evidence` 是展开证据用的结构化数据，`REWIND` 节点为 `null`；
- 无 Git 或不是仓库时 `available=false`，`nodes` 仍包含 run 节点，
  `rewindable=false`；不访问磁盘，重启后仍可查询。

回溯请求：

```json
{"checkpointEntryId": "01JU...", "dryRun": true, "note": "换一种修法"}
```

响应、冲突语义与错误码见 [12 Git 检查点与撤销 §6.3](12-git.md)：

- `dryRun` 缺省为 true，只返回 `files` 与简报，不写文件、不动会话；
- 冲突时 HTTP 200、`rewound=false`、`conflicts` 非空；
- 归档 409 `SESSION_ARCHIVED`，非空闲 409 `INVALID_STATE_TRANSITION`，
  检查点不在当前分支 409 `INVALID_BRANCH`；
- 成功时 `restored` 为实际写的文件，`briefEntryId` 指向新增的回溯简报。
````

In §9, replace item 12 ending with:

```markdown
12. 工具改变工作区后，输入框上方出现“本轮改动”面板：分支、文件数、行数、
   提交建议与逐轮 diff；撤销前二次确认，冲突时展开文件列表并说明原因。
13. 顶栏“时间线”打开右侧决策时间线：按运行列出意图、计划进度、审批、验证与改动，
   展开可看证据；点击“回到这次运行之前”先显示预览（文件、丢弃数量、简报），
   确认后文件与会话一起退回，对话中出现“回溯简报”卡片，下一次请求会带上它。
```

- [ ] **Step 4: Update `docs/11-interface-design.md`**

Add a bullet at the end of `## 4. Web 结构` list:

```markdown
- **决策脊与时间线面板**：聊天区右侧固定一条窄刻度条（决策脊），每个运行一个
  刻度，当前刻度高亮；点击刻度或顶栏“时间线”打开右侧固定面板（窄屏隐藏刻度条，
  面板改为底部抽屉）。每个运行一条节点，节点之间用竖线连接；当前节点高亮，已回溯
  的节点显示为“回溯”节点。节点默认只显示意图、状态与改动摘要，展开后显示计划、
  审批、验证、逐轮文件与 diff。每个可回溯节点有“回到这次运行之前”，点击后先出现
  预览对话框（原生 `<dialog>`），列出将还原的文件、将丢弃的对话数量与回溯简报；
  确认按钮在预览加载完成前禁用。
```

Add three rows to the interaction table, after the “撤销冲突” row:

```markdown
| 时间线打开 | 右侧滑出，遮罩点击或 Esc 关闭；保持面板滚动位置 |
| 回溯预览 | 默认 dry-run，不写文件；取消不发第二次请求 |
| 回溯冲突 | 预览显示冲突文件与原因，确认按钮禁用，不提供强制覆盖 |
```

Append to `## 8. 验证`:

```markdown
- 2026-11-05 追加 M22 时间线走查（独立 8081 实例，Fake 模型 + 临时 Git 仓库 +
  `PI_VERIFY_COMMAND="exit 1"`）：两次 `/write` 加一次拒绝审批共三个节点，右侧决策脊
  出现三个刻度且点击可定位节点；展开证据可见拒绝与失败标记及 diff；在第二个节点
  预览并确认回溯后，文件恢复且会话出现“回溯简报”卡片；390px 下刻度条隐藏、面板为
  底部抽屉，Esc 可关闭；控制台无错误。
```

- [ ] **Step 5: Create `docs/milestones/M22.md`**

```markdown
# M22：决策时间线与任意检查点回溯

> 日期：2026-11-05
> 状态：已完成；`mvn -o test` 全部通过，Checkstyle 通过，Web 浏览器走查完成
> 范围：运行级决策时间线、证据展开、任意检查点预览与回溯、回溯简报、检查点固定

## 目标

M13 的撤销只能退回最近一次运行，用户看到一条被拒的审批或一次错误修改时，无法
知道“如果当时走另一条路会怎样”。M22 把每次运行变成可回看的决策节点：意图、计划、
审批、验证与改动都在时间线上，用户可以预览并回到任意一次运行之前，对话与工作区
一起退回。

与强调异步自治的通用 coding agent 不同，M22 把“人决定走哪条路”放在中心：Agent
负责把每一步的证据摆在桌面上，回退由人确认，且回退结果会明确写回上下文。

## 交付范围

- 领域：复用 `ChatSession.rewind`/`moveLeaf` 与 `BRANCH_SUMMARY`，无新表；
- 应用：
  - `SessionTimelineService` 按当前分支聚合运行节点与证据；
  - `SessionRewindService` 做冲突预检、文件还原、日志截断与简报追加；
  - `RewindBriefBuilder` 生成确定性的“回溯简报”；
  - `BranchSummaryAppender` 在空闲会话上追加简报 entry；
  - `GitCheckpointService` 在运行开始时固定检查点；
- 基础设施：
  - `GitCheckpointPins` 用无父 `commit-tree` + `update-ref` 写内部 ref；
  - `CliGitWorkspace.planRestore` 复用现有冲突预检与还原路径；
  - 删除会话时清理 `refs/pi/checkpoints/<sessionId>/`；
- 触发层：`GET /sessions/{id}/timeline`、`POST /sessions/{id}/rewind`；
  `INVALID_BRANCH` 映射为 409；
- Web：右侧决策脊刻度、时间线面板、证据展开、回溯预览与确认、回溯简报卡片、
  窄屏底部抽屉；
- 配置：`pi.git.pin-checkpoints`，缺省 true，失败降级为 WARNING。

## 关键决策

- 回溯单位是“运行开始前的检查点”，不引入逐工具步进，避免复杂的部分回滚；
- 先预检后写文件，任何冲突都整体放弃，符合 M13 的“要么全退，要么不动”；
- 回溯简报使用现有 `BRANCH_SUMMARY`，自动进入下一次模型上下文，不需要新消息类型；
- 检查点固定是可选的内部 ref，不进分支、不推送；失败不阻断运行；
- 前端只读时间线，不提供人工编辑证据或强制覆盖冲突。

## 验证

- 单元测试覆盖：时间线分组与当前节点、回溯前后叶子与 entry 截断、首轮回溯、
  冲突不写入、非空闲/归档/跨分支错误、简报渲染与确定性；
- 控制器测试覆盖：时间线 DTO、dry-run 与确认、409 错误码；
- 应用级集成测试：两次 run → 时间线 → 回退 → 继续运行，投影上下文包含简报；
- 浏览器走查：两次写文件、拒绝一次审批、一次失败验证，回退到第二个节点之前，
  简报进入下一轮；
- `grep -R "innerHTML" pi-java-app/src/main/resources/static/js` 无结果。

## 已知限制

- 不能回到单轮中间状态；
- 回溯丢弃的对话不能通过时间线恢复；
- 内部 ref 长期积累，只随会话删除清理；
- 时间线只显示当前分支，切换分支后历史节点不合并展示。

## 相关文档

- [设计规格](../superpowers/specs/2026-11-05-decision-timeline-design.md)
- [12 Git 检查点与撤销](../12-git.md)
- [05 API 与 CLI](../05-api-and-cli.md)
- [11 界面设计](../11-interface-design.md)
- [ADR 0005](../adr/0005-git-checkpoints.md)
```

- [ ] **Step 6: Run the full verification suite**

```bash
cd D:/code/pi-java
JAVA_HOME="C:/Users/R9000P/.jdks/ms-21.0.10" mvn -o test
```

Expected: `BUILD SUCCESS`; record the new total test count in `docs/milestones/M22.md`
(the status line must state the real number printed by Maven, not an estimate).

```bash
JAVA_HOME="C:/Users/R9000P/.jdks/ms-21.0.10" mvn -o -q checkstyle:check
```

Expected: no output and exit code 0.

- [ ] **Step 7: Final browser walkthrough**

Repeat the Task 8 walkthrough against the freshly built app on port 8081. Record the
screenshot path (for example `docs/images/timeline.png`) in `docs/11-interface-design.md`.
Do not reuse the Task 8 instance if the code changed after it was started.

- [ ] **Step 8: Commit**

```bash
cd D:/code/pi-java
git add docs/12-git.md docs/adr/0005-git-checkpoints.md docs/05-api-and-cli.md \
  docs/11-interface-design.md docs/milestones/M22.md \
  docs/superpowers/plans/2026-11-05-decision-timeline.md \
  docs/superpowers/specs/2026-11-05-decision-timeline-design.md
git commit -m "docs(m22): record decision timeline and checkpoint rewind"
```



