# ADR 0003：插件 API 零依赖

- 状态：Accepted
- 日期：2026-10-21
- 决策者：pi-java 架构

## 背景

插件需要由第三方独立开发、独立编译、独立升级。如果插件 API 暴露 Spring、Jackson、
MyBatis 或内部 domain 类型，会带来：

- 插件作者被迫绑定宿主框架版本；
- 宿主重构领域模型时破坏第三方插件；
- 类加载器必须暴露大量内部包，隔离形同虚设；
- 插件测试必须启动 Spring 或复制 DTO。

同时，插件运行在宿主 JVM 内，必须明确“类加载隔离不是安全沙箱”。

## 决策

`pi-java-plugin-api` **只依赖 JDK**，不出现：

- Spring / Jakarta / Jackson / MyBatis；
- `com.piagent.domain`、`application`、`infrastructure`、`trigger` 类型；
- 任何注解驱动的序列化契约。

对外只使用：

- `String`、`Path`、`Duration`、`Map<String,Object>`、`List`、record、sealed interface；
- 插件自己的 `PluginToolResult`、`PluginMessage`、`HookEvent`；
- `PluginLogger`、`CancellationToken` 等最小上下文接口。

宿主在 infrastructure 层做映射：

```text
plugin ToolSpec        -> domain ToolDescriptor
plugin PluginToolResult -> domain ToolResult
plugin HookEvent       -> application 事件适配
plugin manifest JSON   -> domain PluginDescriptor
```

类加载策略：

- 每个插件独立 `URLClassLoader`，child-first；
- 父加载器只暴露 JDK 与 `com.piagent.plugin.api`；
- 插件依赖通过 shade 打入自身 jar，`plugin-api` 以 provided 引入；
- 工具名强制 `pluginId.toolName`，禁止覆盖内置工具；
- 注册项实现 `Registration`，卸载时逆序关闭。

## 结果

正面：

- 插件契约稳定、可独立编译与测试；
- 宿主内部重构不会直接破坏插件；
- 类加载隔离边界清晰，插件看不到 Spring/MyBatis/domain；
- 示例插件可作为契约纯度的活文档。

负面：

- 需要一层映射代码（插件类型 ↔ 领域类型）；
- 插件不能复用宿主的工具类与 Jackson 配置；
- 复杂插件需要自己 shade 依赖，jar 可能变大；
- 仅靠类加载器无法阻止插件访问文件/网络/进程。

## 安全声明

插件代码拥有与宿主相同的 JVM 权限。类加载隔离解决的是**类冲突与契约边界**，
不是恶意代码防护。v0.1 只允许安装可信插件，并在文档、安装接口与 UI 中明确提示。
内核的策略、审批、沙箱只约束经过工具管线的调用；插件在自身代码中直接写文件无法被拦截。
插件签名、权限声明、进程外隔离属于 v0.2+。

## 替代方案

### 暴露 domain 类型

插件开发最省事，但领域模型一改就破坏第三方，且父加载器必须暴露内部包，放弃。

### 暴露 Spring `ApplicationContext`

插件可以直接拿 Bean、注册 Bean，边界完全消失，放弃。

### 插件运行在独立进程

隔离最好，但 v0.1 需要额外的 IPC、生命周期与权限模型，复杂度超出范围；
保留为未来演进方向。
