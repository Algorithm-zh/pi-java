# pi-java 文档

所有文档使用中文。文档与代码同步更新：接口、DDL、状态机发生变化时，必须同时修改对应文档与测试。

## 阅读顺序

1. [00 愿景与范围](00-vision-and-scope.md)：产品边界、用户故事、成功标准
2. [01 整体架构](01-architecture.md)：模块、依赖方向、限界上下文、部署形态
3. [02 领域模型](02-domain-model.md)：聚合、实体、值对象、领域服务、端口、不变量
4. [03 数据库设计](03-database-design.md)：6 张表 DDL、ER 关系、索引、Flyway
5. [04 运行时与事件](04-runtime-and-events.md)：Agent Loop、审批、压缩、子代理、SSE
6. [05 API 与 CLI](05-api-and-cli.md)：REST/SSE 契约、CLI 命令、Web UI
7. [06 插件 SDK](06-plugin-sdk.md)：插件契约、生命周期、类加载隔离、示例
8. [07 测试与质量](07-testing-and-quality.md)：测试策略、质量门禁、验收
9. [08 路线图](08-roadmap.md)：M0–M9、依赖、风险、完成定义

## 架构决策记录

- [ADR 0001：会话日志是唯一事实源](adr/0001-session-log-as-source-of-truth.md)
- [ADR 0002：v0.1 不引入 Redis，只使用本地 Caffeine](adr/0002-no-redis-local-cache.md)
- [ADR 0003：插件 API 零依赖](adr/0003-plugin-api-zero-dependency.md)
- [ADR 0004：手写 LLM Gateway](adr/0004-hand-rolled-llm-gateway.md)

## 里程碑记录

- [M0：骨架与文档](milestones/M0.md)
- [M1：会话领域与持久化](milestones/M1.md)
- M2–M9：随里程碑完成补齐

## 模块文档

- [pi-java-domain](../pi-java-domain/README.md)
- [pi-java-application](../pi-java-application/README.md)
- [pi-java-plugin-api](../pi-java-plugin-api/README.md)
- [pi-java-infrastructure](../pi-java-infrastructure/README.md)
- [pi-java-trigger](../pi-java-trigger/README.md)
- [pi-java-app](../pi-java-app/README.md)
- [示例插件](../examples/pi-plugin-example/README.md)
