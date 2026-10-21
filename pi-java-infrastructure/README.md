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
├── tool          read/ls/grep/glob/write/edit/bash、PathResolver、ProcessRunner
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
- 插件卸载等待活跃调用归零，运行中的 run 固定旧快照。

## 测试

- H2 集成测试验证 SQL、唯一键、CAS、级联；
- WireMock 验证流式、重试、取消、畸形响应；
- 临时目录验证路径穿越、符号链接、输出截断；
- Windows/Unix 差异用条件测试覆盖。

## 当前状态

M0：模块与依赖边界已建立。M1 落地 MyBatis 仓储，M2 落地 LLM adapter。
