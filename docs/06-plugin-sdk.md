# 06 插件 SDK

> 状态：M0 冻结。`pi-java-plugin-api` 是第三方稳定契约，只允许依赖 JDK。
> 它是**类加载隔离契约**，不是恶意代码安全沙箱。

## 1. 目标与非目标

目标：

- 第三方可以在不修改内核、不引入 Spring 的情况下扩展工具、命令、hook 与 prompt；
- 插件升级不破坏旧插件：API v1 只增不改；
- 插件工具与内置工具走同一条策略/审批/沙箱管线；
- 插件可以独立编译、独立测试、独立打包。

非目标：

- 不允许插件注册 LLM provider（v0.1）；
- 不允许插件修改工具参数、伪造审批结果；
- 不提供热重载、热部署、插件市场、签名信任链；
- 不承诺插件代码的进程内安全隔离（详见 §7）。

## 2. 契约总览

```text
PiPlugin
├── descriptor() -> PluginDescriptor
├── start(PluginContext)
└── stop()（默认空实现）

PluginContext
├── registerTool(PluginTool)            -> Registration
├── registerCommand(PluginCommand)      -> Registration
├── registerToolInterceptor(...)         -> Registration
├── on(Class<E extends HookEvent>, Consumer<E>) -> Registration
├── addPromptSection(PromptSection)     -> Registration
├── sendMessage(PluginMessage)
├── appendEntry(String customType, String json)
├── workspaceRoot() -> Path
└── log() -> PluginLogger

Registration extends AutoCloseable  // close 注销；卸载时逆序关闭
```

所有类型位于 `com.piagent.plugin.api`，只使用 `java.*` 类型。

## 3. 核心接口

### 3.1 插件入口

```java
public interface PiPlugin {
    PluginDescriptor descriptor();
    void start(PluginContext ctx) throws Exception;
    default void stop() {}
}
```

`start` 抛异常 → 插件状态置 `FAILED`，已完成的注册全部回滚，不影响其他插件。

### 3.2 插件描述

```java
public record PluginDescriptor(
        PluginId id,
        String name,
        String version,       // semver，如 1.2.0
        String apiVersion,    // 主版本必须为 "1"
        String mainClass,
        String description) {}
```

`PluginId` 形如 `com.example.hello`；工具名前缀必须等于 `id`。

### 3.3 工具

```java
public record ToolSpec(
        String name,          // 不含前缀，完整名为 id + "." + name
        String description,
        String inputSchemaJson,
        PluginRisk risk,
        Duration timeout,
        boolean parallelSafe) {}

public interface PluginTool {
    ToolSpec spec();
    PluginToolResult execute(PluginToolCall call, PluginToolContext context) throws Exception;
}

public record PluginToolCall(String id, String name, Map<String, Object> arguments) {}

public record PluginToolResult(
        String content,
        boolean error,
        Map<String, String> metadata) {}

public interface PluginToolContext {
    Path workspaceRoot();
    CancellationToken cancellation();
    PluginLogger log();
    void progress(String partialOutput);
}
```

约束：

- 完整工具名 `pluginId.toolName`，只能匹配 `[a-z0-9_.-]`；
- 不能与内置工具或已激活插件重名；重复注册直接失败；
- `inputSchemaJson` 必须是合法 JSON Schema（由宿主校验，失败则拒绝注册）；
- 工具实现不能假设工作目录，必须使用 `context.workspaceRoot()`；
- `progress` 用于长任务的部分输出，宿主合并为 `tool.updated` 事件；
- 插件工具继承宿主策略：默认未知工具 `DENY`，需要显式配置或审批；
- 插件异常统一转 `error=true` 工具结果，不终止父 run。

### 3.4 命令

```java
public interface PluginCommand {
    String name();              // 完整命令名：pluginId.command
    String description();
    void execute(String args, PluginCommandContext context) throws Exception;
}

public interface PluginCommandContext {
    String sessionId();
    Path workspaceRoot();
    void sendMessage(PluginMessage message);
    PluginLogger log();
}
```

命令只在用户显式输入时执行，不参与 LLM 工具选择。`/hello-world` 这类命令名需要带插件前缀。

### 3.5 Hook

Hook 是**只读观察者**，返回值被忽略，不能阻止执行或修改参数：

```java
public sealed interface HookEvent permits
        SessionStart, BeforeAgentStart, ToolCallObserved,
        ToolResultObserved, TurnEnd, RunEnd {}

public record SessionStart(String sessionId, Path cwd) implements HookEvent {}
public record BeforeAgentStart(String sessionId, String runId, String prompt) implements HookEvent {}
public record ToolCallObserved(String sessionId, String runId, String toolCallId,
                               String toolName, Map<String, Object> arguments) implements HookEvent {}
public record ToolResultObserved(String sessionId, String runId, String toolCallId,
                                 String toolName, boolean error, String outputSummary) implements HookEvent {}
public record TurnEnd(String sessionId, String runId, int turnIndex) implements HookEvent {}
public record RunEnd(String sessionId, String runId, String stopReason) implements HookEvent {}
```

Hook 处理器异常被隔离并记录，不影响主流程；单个处理器默认超时 2 秒（异步执行，v0.1 简单实现）。

### 3.6 工具拦截器

需要在工具执行前否决时，注册单独的拦截器；**只允许 allow/deny，不允许改参数**：

```java
public interface ToolInterceptor {
    InterceptionDecision beforeToolCall(ToolCallObserved event);
}

public record InterceptionDecision(boolean allowed, String reason) {
    public static InterceptionDecision allow() { ... }
    public static InterceptionDecision deny(String reason) { ... }
}
```

拦截器在策略判定之前执行；多个拦截器按注册顺序执行，任一 DENY 即拒绝。
拦截器不能覆盖内置策略的 DENY，也不能把 ASK 降级为 ALLOW。

### 3.7 Prompt Section

```java
public record PromptSection(String id, String title, String content, int order) {}
```

- 注入 system prompt 的插件区，按 `order` 升序拼接；
- 单 section ≤ 8 KB，所有 section 合计 ≤ 16 KB；
- 内容不允许包含密钥；宿主记录 section 来源以便审计；
- 与 Skills 的 name/description 列表分开展示。

### 3.8 消息与状态

```java
public record PluginMessage(String text, boolean display) {}

void sendMessage(PluginMessage message);      // 写入 CUSTOM_MESSAGE entry，并推 SSE
void appendEntry(String customType, String json); // 写入 CUSTOM entry，不进入 LLM 上下文
```

- `sendMessage` 的 `display=true` 时在 UI/CLI 显示；`false` 时静默注入上下文；
- `appendEntry` 只允许 JSON 字符串，单条 ≤ 64 KB；用于插件自己的状态恢复；
- 两者都受当前 run/session 生命周期约束，不能在 `start` 阶段写入（此时无会话）。

### 3.9 日志与取消

```java
public interface PluginLogger {
    void debug(String message, Object... args);
    void info(String message, Object... args);
    void warn(String message, Object... args);
    void error(String message, Throwable error, Object... args);
}

public interface CancellationToken {
    boolean isCancelled();
    void onCancel(Runnable action);
}
```

日志自动带 `pluginId` 前缀；参数中的疑似密钥由宿主脱敏。
`onCancel` 在 run/工具取消时触发；回调必须快速返回，不能阻塞。

## 4. 打包与清单

插件是一个普通 jar，根目录包含 `META-INF/pi-plugin.json`：

```json
{
  "id": "com.example.hello",
  "name": "Hello Plugin",
  "version": "1.0.0",
  "apiVersion": "1",
  "mainClass": "com.example.hello.HelloPlugin",
  "description": "示例：注册 hello 工具、会话开始 hook 与 /hello 命令"
}
```

规则：

- `plugin-api` 以 `provided` 依赖，不打入插件 jar；
- 其他依赖通过 shade 打入插件 jar，避免类加载器互相污染；
- jar 根目录不允许包含 `com.piagent.plugin.api` 下的类（重复契约直接拒绝）；
- 安装时校验 SHA-256、manifest 必填字段、主类实现 `PiPlugin`；
- 插件配置以 JSON 存入 `pi_plugin.config_json`，通过 `PluginContext` 读取的扩展点留到 v0.2。

## 5. 生命周期

```text
UPLOAD -> VERIFIED -> INSTALLED -> ACTIVE
                         │           │
                         │           ├── disable -> DISABLED
                         │           └── error   -> FAILED
                         └── enable ─────────────┘
```

1. **安装**：读取 jar、校验 checksum 与 manifest、检查 API 主版本、检查工具名冲突；
2. **启用**：创建独立 `URLClassLoader`，实例化主类，调用 `start`，注册项写入运行时；
3. **运行**：每次 run 开始时对工具目录取不可变快照，run 内固定使用该快照；
4. **禁用**：状态先置 `DISABLED`，停止接受新调用；等待活跃调用计数归零；
5. **卸载**：按注册逆序 `close()`，调用 `stop()`，关闭 ClassLoader；
6. **失败**：记录 `last_error`，回滚本次注册，不影响其他插件与已运行的 run。

运行中的 run 持有旧快照与旧 ClassLoader 引用；禁用不会让正在执行的工具突然失败。

## 6. 类加载隔离

```text
Bootstrap / Platform
        ▲
        │ java.*
Host PluginApiClassLoader（只暴露 plugin-api）
        ▲
        │
PluginClassLoader（child-first，加载插件自身类与 shaded 依赖）
```

- 父加载器只暴露 JDK 与 `com.piagent.plugin.api`；
- 插件无法看到 Spring、MyBatis、domain、application 与宿主内部类；
- 插件之间的类不共享；同名类不会互相覆盖；
- 线程上下文类加载器在调用插件期间切换为插件 ClassLoader，返回后恢复；
- SPI/反射实例化只允许 manifest 指定的主类；
- `System.setSecurityManager` 在 Java 21 已不可用，因此**不能**用类加载器阻止插件访问文件/网络/进程；
  这是隔离，不是安全边界。

## 7. 安全边界（必须明确）

- 只安装可信来源的插件；安装前的 checksum 只保证完整性，不保证作者可信；
- 插件代码拥有与宿主相同的 JVM 权限，可以读写文件、发起网络请求、启动进程；
- 内核的策略/审批/沙箱只约束**通过工具管线**的调用；插件若在自己的代码里直接操作文件，宿主无法拦截；
- 因此插件文档必须提示：不要在插件中执行未经用户同意的写操作；
- v0.1 不提供插件权限声明、签名校验、进程外隔离；这些是 v0.2+ 的议题。

## 8. API 版本策略

- `apiVersion` 采用 `MAJOR.MINOR`；宿主只加载主版本 `1`；
- MINOR 升级只允许新增 default 方法、新事件类型、新 record 字段（保持构造兼容）；
- 破坏性变更必须升主版本，并提供并行加载能力；
- 工具 schema 与命令名一旦发布，按插件自身 semver 管理；
- 事件类型新增用 sealed `permits` 扩展，旧插件不实现新处理器即可。

## 9. 示例插件

`examples/pi-plugin-example` 演示三件事：

1. 注册 `hello` 工具：输入 `name`，返回问候；
2. 注册 `SessionStart` hook：记录会话开始日志；
3. 注册 `hello` 命令：向当前会话发送一条插件消息。

核心类：

```java
public final class HelloPlugin implements PiPlugin {
    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor(
                PluginId.parse("com.example.hello"),
                "Hello Plugin", "1.0.0", "1",
                HelloPlugin.class.getName(),
                "示例插件");
    }

    @Override
    public void start(PluginContext ctx) {
        ctx.registerTool(new HelloTool());
        ctx.on(SessionStart.class, event -> ctx.log().info("session started: {}", event.sessionId()));
        ctx.registerCommand(new HelloCommand());
    }
}
```

完整工具名是 `com.example.hello.hello`；模型看到的名字带前缀，避免与内置工具冲突。

## 10. 测试要求

插件契约的测试分三层：

1. **契约纯度**：ArchUnit 验证 `plugin-api` 只依赖 `java..`；
2. **独立编译**：示例插件只依赖 `pi-java-plugin-api`（provided），不依赖宿主；
3. **宿主集成**：安装 → enable → 工具出现在 `/tools` → 调用成功 → disable → 工具消失 → 运行中的 run 仍可完成。

插件自身测试不启动 Spring：用内存实现 `PluginContext` 验证注册与回滚逻辑。

## 11. 相关文档

- [领域模型](02-domain-model.md)
- [运行时与事件](04-runtime-and-events.md)
- [测试与质量](07-testing-and-quality.md)
