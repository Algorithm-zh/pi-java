# 代码讲解进度台账

> 由内置技能 `codebase-tutorial`（`pi-java-app/src/main/resources/skills/codebase-tutorial/SKILL.md`）驱动。
> 讲解顺序与七段式结构见该技能定义。

| 序号 | 模块 | 状态 | 备注 |
|---|---|---|---|
| 1 | `domain.shared` 共享内核 | ✅ 已讲 | ULID / IdGenerator / DomainErrorCode / DomainException / Guard |
| 2 | `domain.session` 会话 | ⬜ 待讲 | 下一讲：ChatSession、SessionEntry、消息 sealed、投影器 |
| 3 | `domain.model` 模型 | ⬜ | |
| 4 | `domain.tool` 工具 | ⬜ | |
| 5 | `domain.governance` 治理 | ⬜ | |
| 6 | `domain.agent` 运行时 | ⬜ | |
| 7 | `domain.context` 上下文 | ⬜ | |
| 8 | `domain.extension` 扩展 | ⬜ | |
| 9 | `domain.subagent` 子代理 | ⬜ | |
| 10 | application → infrastructure → trigger → app | ⬜ | domain 全部讲完后开始 |

## 第 1 讲要点回执（domain.shared）

- 已核对的真实文件：`Ulid.java`、`IdGenerator.java`、`DomainException.java`、`DomainErrorCode.java`（40 个枚举常量）、`Guard.java`、两个 `package-info.java`、`UlidTest.java`、`CoreConfiguration.java:37-40`。
- `IdGenerator` 的全仓库唯一实现：`infrastructure/config/CoreConfiguration#idGenerator`，lambda `() -> Ulid.generate(clock.instant()).value()`。
- 给出的实现取舍结论：ULID 只保证跨毫秒有序，同毫秒内顺序随机（无单调计数器）；该结论由 `Ulid.java:58-60` 与 `UlidTest.java:14-22` 的断言范围共同支持。

## 遗留疑问 / 待确认

- 第 1 讲讲义正文未落盘到 `docs/`（台账原先已标 ✅ 但无对应文件），如有需要可另存为 `docs/tutorial/01-domain-shared.md`。
- 待第 2 讲确认：`EntryId`/`SessionId`/`RunId` 是否都通过 `Ulid.parse` 做二次校验，或直接信任 `IdGenerator` 输出。
