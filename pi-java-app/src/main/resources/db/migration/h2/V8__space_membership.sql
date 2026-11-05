-- M21：账号全局化与空间成员关系（H2 MySQL 模式）。
-- 与 mysql/V8__space_membership.sql 保持同样的版本号与语义，只调整方言。
-- 升级前每个账号只属于一个空间，回填为一条成员关系；既有角色与状态保留。

ALTER TABLE pi_tenant ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'TEAM';

UPDATE pi_tenant SET kind = 'DEFAULT' WHERE id = '00000000000000000000000001';

CREATE TABLE pi_space_member (
    tenant_id  CHAR(26)     NOT NULL,
    user_id    CHAR(26)     NOT NULL,
    role       VARCHAR(16)  NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (tenant_id, user_id),
    CONSTRAINT fk_member_tenant FOREIGN KEY (tenant_id) REFERENCES pi_tenant (id),
    CONSTRAINT fk_member_user FOREIGN KEY (user_id) REFERENCES pi_app_user (id)
        ON DELETE CASCADE
);

CREATE INDEX idx_member_user ON pi_space_member (user_id);

INSERT INTO pi_space_member (tenant_id, user_id, role, created_at)
SELECT tenant_id, id, role, created_at FROM pi_app_user;

ALTER TABLE pi_app_user DROP CONSTRAINT fk_user_tenant;
ALTER TABLE pi_app_user DROP CONSTRAINT uk_user_tenant_username;
DROP INDEX idx_user_tenant;
ALTER TABLE pi_app_user DROP COLUMN tenant_id;
ALTER TABLE pi_app_user DROP COLUMN role;
