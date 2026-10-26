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
9. [08 路线图](08-roadmap.md)：M0–M14 与 v0.2 规划、依赖、风险、完成定义
10. [09 快速上手](09-quick-start.md)：启动脚本、接入真实模型、技能与常见问题
11. [10 MCP 客户端](10-mcp.md)：配置、传输、命名空间、风险映射、断线与超时
12. [11 界面设计](11-interface-design.md)：设计原则、视觉令牌、Web/TUI 交互与可访问性
13. [12 Git 检查点与撤销](12-git.md)：快照、逐轮 diff、撤销语义、API/CLI/Web 与限制
14. [13 计划、待办与自动验证](13-plans-and-verification.md)：`todo` 工具、验证命令、失败回写与展示

## 架构决策记录

- [ADR 0001：会话日志是唯一事实源](adr/0001-session-log-as-source-of-truth.md)
- [ADR 0002：v0.1 不引入 Redis，只使用本地 Caffeine](adr/0002-no-redis-local-cache.md)
- [ADR 0003：插件 API 零依赖](adr/0003-plugin-api-zero-dependency.md)
- [ADR 0004：手写 LLM Gateway](adr/0004-hand-rolled-llm-gateway.md)
- [ADR 0005：临时索引检查点](adr/0005-git-checkpoints.md)

## 里程碑记录

- [M0：骨架与文档](milestones/M0.md)
- [M1：会话领域与持久化](milestones/M1.md)
- [M2：模型接入与流式 LLM Gateway](milestones/M2.md)
- [M3：只读工具与路径沙箱](milestones/M3.md)
- [M4：Agent Loop 与只读 Web demo](milestones/M4.md)
- [M5：插件 SDK](milestones/M5.md)
- [M6：审批与写工具](milestones/M6.md)
- [M7：上下文压缩与 Skills](milestones/M7.md)
- [M8：同步子智能体](milestones/M8.md)
- [M9：Web UI 完善与开源准备](milestones/M9.md)
- [M10：界面重构 · 信号工作台](milestones/M10.md)
- [M11：CLI 与 v0.1.0 收尾](milestones/M11.md)
- [M12：MCP 客户端](milestones/M12.md)
- [M13：Git 检查点](milestones/M13.md)
- [M14：计划、待办与自动验证](milestones/M14.md)

## 模块文档

- [pi-java-domain](../pi-java-domain/README.md)
- [pi-java-application](../pi-java-application/README.md)
- [pi-java-plugin-api](../pi-java-plugin-api/README.md)
- [pi-java-infrastructure](../pi-java-infrastructure/README.md)
- [pi-java-trigger](../pi-java-trigger/README.md)
- [pi-java-app](../pi-java-app/README.md)
- [示例插件](../examples/pi-plugin-example/README.md)
