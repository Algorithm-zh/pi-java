# 06 插件 SDK

> 状态：M5 已实现。`pi-java-plugin-api` 只依赖 JDK，是第三方稳定契约；
> 当前范围为**工具注册 + 日志 + 取消 + 安装/启停生命周期**。
> 它是**类加载隔离契约**，不是恶意代码安全沙箱。

## 1. 目标与非目标

目标：

- 第三方在不修改内核、不引入 Spring 的情况下注册工具；
- 插件独立编译、独立测试、独立打包；
- 插件工具与内置工具走同一条策略、沙箱与 run 快照管线；
- API v1 只增不改，旧插件不因宿主升级而重新编译。

非目标（v0.1）：

- 不提供命令、hook、工具拦截器、prompt section、插件消息与自定义 entry；
- 不允许插件注册 LLM provider；
- 不允许插件修改工具参数或伪造策略结果；
- 不提供热重载、插件市场、签名信任链与进程外隔离；
- 不承诺插件代码的进程内安全隔离（见 §7）。

## 2. 契约总览

```text
PiPlugin
├── descriptor() -> PluginDescriptor
├── start(PluginContext)
└── stop()（默认空实现）

PluginContext
├── registerTool(PluginTool) -> Registration
└── log() -> PluginLogger

PluginTool
├── spec() -> ToolSpec
└── execute(PluginToolCall, PluginToolContext) -> PluginToolResult

PluginToolContext
├── workspaceRoot() -> Path
├── cancellation() -> CancellationToken
├── log() -> PluginLogger
└── progress(String)
```

所有类型位于 `com.piagent.plugin.api`，只使用 `java.*` 类型。
`PluginContext` 的其余扩展点按里程碑追加，通过 default 方法或新增类型保持主版本不变。

## 3. 核心接口

### 3.1 插件入口

```java
public interface PiPlugin {
    PluginDescriptor descriptor();
    void start(PluginContext ctx) throws Exception;
    default void stop() {}
}
```

`start` 抛异常 → 本次加载失败：宿主关闭注册、调用 `stop()`、关闭类加载器，
插件状态落库为 `FAILED`，不影响其他插件。

### 3.2 插件描述

```java
public record PluginDescriptor(
        PluginId id,
        String name,
        String version,       // semver，如 1.0.0
        String apiVersion,    // 主版本必须为 1
        String mainClass,
        String description) {}
```

- `PluginId` 形如 `com.piagent.example.hello`，只允许小写字母、数字、点与连字符；
- `descriptor()` 必须与 jar 内清单一致（id、entry class、version、apiVersion），
  否则拒绝加载；
- 完整工具名由宿主生成为 `pluginId + "." + toolName`，插件不能自带前缀。
  内部名称保留点号；发送给 OpenAI-compatible 模型时适配层会清洗成合法函数名
  （非法字符换成 `_`、超长截断），收到工具调用后再还原，策略与审批仍按完整名称匹配。

### 3.3 工具

```java
public record ToolSpec(
        String name,          // 插件内短名，匹配 [a-z][a-z0-9_-]{0,63}
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
```

约束：

- 短名不得包含 `.`，宿主统一加插件前缀，避免二次前缀与内置工具冲突；
- `inputSchemaJson` 必须解析为 JSON 对象，否则加载失败；不校验完整 JSON Schema 语义；
- 同一插件内短名不得重复；与内置工具或其他已加载插件重名时，整次加载失败；
- 描述 ≤ 1024 字符，schema ≤ 20000 字符，超时为正且 ≤ 10 分钟；
- 工具实现不能假设进程工作目录，相对路径必须以 `context.workspaceRoot()` 为根；
- 插件异常与 `null` 返回值统一转成 `error=true` 的工具结果，不终止父 run；
- `metadata` 会写入工具结果 `details`，只允许字符串键值。

### 3.4 执行上下文

```java
public interface PluginToolContext {
    Path workspaceRoot();
    CancellationToken cancellation();
    PluginLogger log();
    void progress(String partialOutput);
}

public interface CancellationToken {
    boolean isCancelled();
}
```

- `workspaceRoot()` 是当前会话沙箱根，不是进程 cwd；
- `cancellation()` 反映执行线程的中断状态，插件应在长循环中周期性检查；
- `progress()` 在 M5 保留契约但不产生事件，工具事件流接入后启用。

### 3.5 日志

```java
public interface PluginLogger {
    void debug(String message, Object... args);
    void info(String message, Object... args);
    void warn(String message, Object... args);
    void error(String message, Throwable error, Object... args);
}
```

日志自动带 `pluginId` 前缀。宿主不做内容脱敏，插件不得输出密钥、令牌等敏感信息。

### 3.6 注册句柄

`Registration extends AutoCloseable`：`close()` 注销对应注册项，可重复调用。
插件自己关闭后注册项不再生效；加载失败时宿主按逆序统一关闭。

## 4. 打包与清单

插件是普通 jar，根目录包含 `META-INF/pi-plugin.json`（示例插件原文）：

```json
{
  "id": "com.piagent.example.hello",
  "name": "Hello Plugin",
  "version": "1.0.0",
  "apiVersion": "1.0",
  "mainClass": "com.piagent.example.HelloPlugin",
  "description": "示例插件：演示零依赖契约与工具注册"
}
```

规则：

- `plugin-api` 以 `provided` 依赖，不打入插件 jar；
- 其他依赖通过 shade 打入插件 jar，避免插件之间共享可变的类；
- jar 内不允许包含 `com/piagent/plugin/api/` 下的类，发现即拒绝；
- jar 必须是普通文件且 ≤ 64 MB，清单 ≤ 16 KB；
- 安装时计算 SHA-256 并落库；启用时重新检查 jar，id/version/apiVersion/checksum
  任一不一致即拒绝加载（防止安装后替换 jar）；
- `config` JSON 随安装记录保存，但 M5 的 `PluginContext` 不读取它，留到 v0.2。

`GET /api/v1/plugins` 与安装/启停响应会附带清单里的 `name` 与 `description`
（由控制器读取 jar 补全），Web 设置页直接展示这两个字段；jar 被删除或损坏时
名称回退为插件标识，安装记录本身仍然可见。

## 5. 生命周期

```text
install ──► INSTALLED ──enable──► ACTIVE ──disable──► DISABLED
                 │                    │                    │
                 │                    └──load error──► FAILED
                 └──────────────enable────────────────────┘
```

| 状态 | 含义 |
|---|---|
| `INSTALLED` | 已校验并落库，代码未加载，工具不可见 |
| `ACTIVE` | 运行时已加载，工具进入实时目录 |
| `DISABLED` | 用户停用，工具不在实时目录 |
| `FAILED` | 最近一次加载失败，`last_error` 记录原因，可再次 enable 重试 |

1. **安装**：校验 jar/清单/API 主版本/重复 id，只落库，不加载代码；
2. **启用**：创建 child-first `PluginClassLoader` → 实例化入口类 → 校验 descriptor
   → `start()` 注册工具 → 校验工具名与 schema → 发布到实时目录 → 状态置 `ACTIVE`；
   任一步失败都会回滚本次注册并落库为 `FAILED`；
3. **运行**：run 开始时对工具目录取不可变快照，run 内始终使用该快照；
4. **停用**：立即从实时目录移除并置 `DISABLED`；**不等待**活跃调用归零，也不立即
   `stop()`。已启动的 run 仍持有旧快照、工具句柄与旧类加载器，可以正常完成；
5. **卸载**：v0.1 没有独立卸载接口；停用的插件资产保留到进程退出，
   应用关闭时统一 `context.close()` → `stop()` → 关闭类加载器；
6. **启动恢复**：应用启动后重新加载所有 `ACTIVE` 记录，单个失败只标记该插件
   `FAILED`，不阻止应用启动。

已知限制：

- 没有热重载；修改 jar 后必须重新安装并重启进程；
- 停用后旧类加载器不会释放，插件数量多时会有内存累积；
- 没有活跃调用计数，也没有“停用后等待任务结束”的 UI 状态。

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
- 插件看不到 Spring、MyBatis、domain、application 与宿主内部类；
- 插件之间的类不共享，同名类不会互相覆盖；
- 调用插件工具与 `start()` 期间，线程上下文类加载器切换为插件加载器，返回后恢复；
- 反射实例化只允许清单指定的主类。

## 7. 安全边界（必须明确）

- 只安装可信来源的插件；SHA-256 只保证完整性，不保证作者可信；
- 插件代码拥有与宿主相同的 JVM 权限，可以读写文件、发起网络请求、启动进程；
- 策略与沙箱只约束**通过工具管线**的调用；插件在自己的代码里直接操作文件时，
  宿主无法拦截；
- `System.setSecurityManager` 在 Java 21 已不可用，类加载器不是安全边界；
- v0.1 不提供权限声明、签名校验、进程外隔离；这些是 v0.2+ 的议题。

## 8. API 版本策略

- 清单 `apiVersion` 接受 `MAJOR` 或 `MAJOR.MINOR`，内部规范化为 `major.minor`；
  示例与本文统一写 `"1.0"`；
- 宿主只加载主版本 `1`；
- MINOR 升级只允许新增 default 方法、新类型或新 record 字段（保持构造兼容）；
- 破坏性变更必须升主版本，并提供并行加载能力；
- `descriptor().apiVersion` 与清单按规范化后的版本比较，`"1"` 与 `"1.0"` 等价。

## 9. 示例插件

`examples/pi-plugin-example` 只依赖 `pi-java-plugin-api`：

- `com.piagent.example.HelloPlugin`：入口类；
- `com.piagent.example.HelloTool`：注册短名 `greet`，返回中文问候；
- `META-INF/pi-plugin.json`：清单（见 §4）。

```java
public final class HelloPlugin implements PiPlugin {
    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor(
                new PluginId("com.piagent.example.hello"),
                "Hello Plugin", "1.0.0", "1.0",
                HelloPlugin.class.getName(),
                "示例插件：演示零依赖契约与工具注册");
    }

    @Override
    public void start(PluginContext ctx) {
        ctx.registerTool(new HelloTool());
    }
}
```

完整工具名是 `com.piagent.example.hello.greet`；模型看到的名字带插件前缀。

## 10. 未实现扩展点

以下能力只在早期设计稿中出现，M5 **没有**契约，也没有对应实现：

| 能力 | 计划 |
|---|---|
| 插件命令 `/pluginId.command` | M6 之后按需引入 |
| Hook（会话/工具/run 事件） | M6/M7 按事件稳定性引入 |
| 工具拦截器（allow/deny） | M6 审批落地后评估 |
| Prompt section | M7 与 Skills 一起设计 |
| `sendMessage` / `appendEntry` | 需要 CUSTOM entry 的消费方后再加 |
| 插件配置读取 | v0.2 |

新增这些能力时必须在 `plugin-api` 中以兼容方式扩展，并同步本文与示例。

## 11. 测试要求

插件契约的测试分四层：

1. **契约纯度**：ArchUnit 验证 `plugin-api` 只依赖 `java..`；
2. **独立编译**：示例插件只依赖 `pi-java-plugin-api`（provided），不依赖宿主；
3. **宿主集成**：安装只落库 → enable 后 `/api/v1/tools` 出现 → 调用成功 →
   disable 后工具消失；重复安装 409、未知插件 404、缺失 jar 422；
4. **端到端**：Fake 模型通过 `/tool <name> <json>` 调用插件工具，验证
   `tool.completed` 与结果内容；停用后旧 run 完成、新调用得到“工具未注册”。

插件自身测试不启动 Spring；需要宿主编译期类型的测试放在 `pi-java-app` 测试夹具中。

## 12. 相关文档

- [领域模型](02-domain-model.md)
- [运行时与事件](04-runtime-and-events.md)
- [API 与 CLI](05-api-and-cli.md)
- [测试与质量](07-testing-and-quality.md)
