# pi-java-plugin-api

第三方插件稳定契约。**只依赖 JDK**，不出现 Spring、Jackson、MyBatis 或内部 domain 类型。

## 契约内容（M5）

- `PiPlugin`：插件入口，`descriptor / start / stop`；
- `PluginDescriptor` / `PluginId`：插件自描述与标识；
- `PluginContext`：注册工具、获取日志器；
- `ToolSpec` / `PluginTool`：工具短名、schema、风险、超时与执行；
- `PluginToolCall` / `PluginToolResult`：调用参数与结果；
- `PluginToolContext`：工作区、取消令牌、日志、进度上报（M5 空实现）；
- `PluginLogger`、`CancellationToken`、`Registration`。

命令、hook、工具拦截器与 prompt section **不在 M5 契约中**，按后续里程碑以兼容方式加入。

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

M5 已落地上述契约；3 个单元测试与 ArchUnit 纯度检查通过。示例插件 4 个测试、
100% 行覆盖。详见 [M5 记录](../docs/milestones/M5.md)。
