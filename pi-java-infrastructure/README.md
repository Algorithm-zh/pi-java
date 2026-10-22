# pi-java-infrastructure

基础设施适配器。实现 domain/application 定义的端口，不包含业务规则。

## 依赖

`pi-java-domain`、`pi-java-application`、`pi-java-plugin-api`、驱动与工具库。

**禁止**依赖 `pi-java-trigger`。

## 计划包结构

```text
com.piagent.infrastructure
├── persistence   MyBatis mapper、PO、仓储实现
├── llm           Fake / OpenAI-compatible provider，SSE 解析
├── tool          read/ls/grep/glob/write/edit/bash、RealPathResolver、ProcessRunner
├── plugin        manifest、ClassLoader、注册表、插件运行时
├── skill         目录扫描、frontmatter 解析
├── cache         Caffeine 缓存实现
└── config        Spring 装配
```

## 规则

- PO 与领域对象分离，JSON payload 编解码集中在 persistence；
- 模型密钥只从环境变量读取，不落库、不进日志；
- SSH/文件/进程访问都通过端口注入，便于测试；
- LLM 重试只在 adapter 内，且首个 delta 后不重试；
- 插件停用立即移出实时目录；已开始的 run 使用旧快照，插件实例与类加载器保留到进程退出。

## 测试

- H2 集成测试验证 SQL、唯一键、CAS、级联；
- WireMock 验证流式、重试、取消、畸形响应；
- 临时目录验证路径穿越、符号链接、输出截断；
- Windows/Unix 差异用条件测试覆盖。

## 当前状态

M1 完成 `persistence.session`（PO/Mapper/仓储、payload 与消息 JSON 编解码、Flyway V1）；
M2 完成 `persistence.model`（V2、CAS 更新、capabilities codec）与 `llm`
（Fake 脚本 provider、provider 路由、OpenAI-compatible 的请求映射/SSE 解析/重试/取消）；
M3 完成 `tool`（RealPathResolver、read/ls/grep/glob、虚拟线程超时调用器）与
`persistence.model` 下的 V3 工具策略仓储；M4 增加 demo 模式的
`DemoTurnScript` 与 `DemoDataInitializer` 依赖的 Fake 脚本；M5 完成 `plugin`
（`PluginJarInspector`、child-first `PluginClassLoader`、`PluginHost`、
`RuntimeToolRegistry`、`RuntimeToolInvoker`）与 V4 插件记录仓储，并为 Fake demo
增加 `/tool <name> <json>`。infrastructure 共 135 个测试（2 个符号链接用例在无权限
环境条件跳过），行覆盖 65.8%。详见 [M5 记录](../docs/milestones/M5.md)。
