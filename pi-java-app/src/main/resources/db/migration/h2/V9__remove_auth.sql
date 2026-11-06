-- M24：去账号化（H2 MySQL 模式）。
-- 与 mysql/V9__remove_auth.sql 保持同样的版本号与语义，只调整方言。
-- 保留会话与工具策略，先按新唯一键去重，再删除租户列与身份表。

DELETE FROM pi_tool_policy
WHERE id NOT IN (
    SELECT id FROM (
        SELECT MIN(id) AS id
        FROM pi_tool_policy
        GROUP BY scope, workspace_key, tool_name
    ) kept
);

ALTER TABLE pi_tool_policy DROP CONSTRAINT uk_tool_policy;
ALTER TABLE pi_tool_policy DROP CONSTRAINT fk_policy_tenant;
ALTER TABLE pi_tool_policy DROP COLUMN tenant_id;
ALTER TABLE pi_tool_policy
    ADD CONSTRAINT uk_tool_policy UNIQUE (scope, workspace_key, tool_name);

ALTER TABLE pi_session DROP CONSTRAINT fk_session_tenant;
DROP INDEX idx_session_tenant_updated;
ALTER TABLE pi_session DROP COLUMN tenant_id;

DROP TABLE pi_space_member;
DROP TABLE pi_auth_token;
DROP TABLE pi_app_user;
DROP TABLE pi_tenant;
