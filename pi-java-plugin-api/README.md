# pi-java-plugin-api

第三方插件稳定契约。**只依赖 JDK**，不出现 Spring、Jackson、MyBatis 或内部 domain 类型。

## 契约内容

- `PiPlugin`：插件入口；
- `PluginContext`：注册工具、命令、hook、prompt section，发送消息、追加状态；
- `ToolSpec` / `PluginTool`：工具描述与执行；
- `PluginCommand`：用户显式命令；
- sealed `HookEvent`：只读生命周期观察；
- `ToolInterceptor`：只允许 allow/deny，不允许改参数；
- `PromptSection`、`PluginLogger`、`CancellationToken`、`Registration`。

## 规则

- 工具完整名 = `pluginId.toolName`，禁止覆盖内置工具；
- 插件依赖通过 shade 打入自身 jar，本 API 以 `provided` 引入；
- 每个插件独立 ClassLoader，父加载器只暴露 JDK 与本 API；
- 类加载隔离不是恶意代码安全沙箱；只安装可信插件；
- API 主版本必须为 1；只增不改，破坏性变更升主版本。

## 示例

```bash
mvn -q -pl examples/pi-plugin-example -am package
```

示例插件只依赖本模块，验证契约可独立编译。详见
[插件 SDK](../docs/06-plugin-sdk.md)。

## 当前状态

M0：包结构已建立；接口从 M5 开始落地。
