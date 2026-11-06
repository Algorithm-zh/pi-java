-- M24：去账号化（MySQL 8）。
-- 保留会话与工具策略，先按新唯一键去重，再删除租户列与身份表。

DELETE FROM pi_tool_policy
WHERE id NOT IN (
    SELECT id FROM (
        SELECT MIN(id) AS id
        FROM pi_tool_policy
        GROUP BY scope, workspace_key, tool_name
    ) kept
);

ALTER TABLE pi_tool_policy DROP INDEX uk_tool_policy;

ALTER TABLE pi_tool_policy DROP FOREIGN KEY fk_policy_tenant;

ALTER TABLE pi_tool_policy DROP COLUMN tenant_id;

ALTER TABLE pi_tool_policy
    ADD UNIQUE KEY uk_tool_policy (scope, workspace_key, tool_name);

ALTER TABLE pi_session DROP FOREIGN KEY fk_session_tenant;

ALTER TABLE pi_session DROP INDEX idx_session_tenant_updated;

ALTER TABLE pi_session DROP COLUMN tenant_id;

DROP TABLE pi_space_member;

DROP TABLE pi_auth_token;

DROP TABLE pi_app_user;

DROP TABLE pi_tenant;
