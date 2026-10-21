# H2 迁移脚本

Flyway 在 `demo`、`test` profile 下扫描本目录，按版本号顺序执行 `V*.sql`。

- 表结构与 MySQL 版本保持**语义一致**，只允许在索引、列类型等方言层面不同；
- 首个脚本 `V1__session.sql` 已随 M1 加入（会话与 entry 两张表）；
- 脚本一经提交不得修改，只能新增更高版本迁移；
- 本地文件库位于 `./data/pi-java.mv.db`，已在 `.gitignore` 中排除。
