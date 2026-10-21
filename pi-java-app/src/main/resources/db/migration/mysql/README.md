# MySQL 迁移脚本

Flyway 在 `mysql` profile 下扫描本目录，按版本号顺序执行 `V*.sql`。

- 使用 MySQL 8 方言（`DATETIME(3)`、`LONGTEXT`、`utf8mb4`）；
- 与 H2 目录中的迁移保持相同的表、列、约束语义；
- 首个脚本 `V1__session.sql` 已随 M1 加入（会话与 entry 两张表）；
- 已发布的迁移禁止修改，修复只能通过新增版本完成。
