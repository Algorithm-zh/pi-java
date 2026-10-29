-- M19：租户、本地账号与访问令牌（H2 MySQL 模式）。
-- 与 mysql/V6__tenant_auth.sql 保持同样的版本号与语义，只调整方言。
-- 默认租户使用固定 ID，存量会话与策略在升级时自动归入该租户。

CREATE TABLE pi_tenant (
    id         CHAR(26)     NOT NULL,
    slug       VARCHAR(64)  NOT NULL,
    name       VARCHAR(128) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_tenant_slug UNIQUE (slug)
);

INSERT INTO pi_tenant (id, slug, name, created_at)
VALUES ('00000000000000000000000001', 'default', '默认空间', CURRENT_TIMESTAMP);

CREATE TABLE pi_app_user (
    id            CHAR(26)     NOT NULL,
    tenant_id     CHAR(26)     NOT NULL,
    username      VARCHAR(64)  NOT NULL,
    display_name  VARCHAR(128) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(16)  NOT NULL,
    status        VARCHAR(16)  NOT NULL,
    created_at    TIMESTAMP(3) NOT NULL,
    updated_at    TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_user_tenant_username UNIQUE (tenant_id, username),
    CONSTRAINT fk_user_tenant FOREIGN KEY (tenant_id) REFERENCES pi_tenant (id)
);

CREATE INDEX idx_user_tenant ON pi_app_user (tenant_id);

CREATE TABLE pi_auth_token (
    id           CHAR(26)     NOT NULL,
    tenant_id    CHAR(26)     NOT NULL,
    user_id      CHAR(26)     NOT NULL,
    token_hash   CHAR(64)     NOT NULL,
    issued_at    TIMESTAMP(3) NOT NULL,
    expires_at   TIMESTAMP(3) NOT NULL,
    last_used_at TIMESTAMP(3) NULL,
    revoked_at   TIMESTAMP(3) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_auth_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_auth_token_tenant FOREIGN KEY (tenant_id) REFERENCES pi_tenant (id),
    CONSTRAINT fk_auth_token_user FOREIGN KEY (user_id) REFERENCES pi_app_user (id)
        ON DELETE CASCADE
);

CREATE INDEX idx_auth_token_user ON pi_auth_token (user_id);
CREATE INDEX idx_auth_token_expiry ON pi_auth_token (expires_at);

ALTER TABLE pi_session
    ADD COLUMN tenant_id CHAR(26) NOT NULL DEFAULT '00000000000000000000000001';

ALTER TABLE pi_session
    ADD CONSTRAINT fk_session_tenant FOREIGN KEY (tenant_id) REFERENCES pi_tenant (id);

CREATE INDEX idx_session_tenant_updated ON pi_session (tenant_id, updated_at);

ALTER TABLE pi_tool_policy
    ADD COLUMN tenant_id CHAR(26) NOT NULL DEFAULT '00000000000000000000000001';

ALTER TABLE pi_tool_policy
    ADD CONSTRAINT fk_policy_tenant FOREIGN KEY (tenant_id) REFERENCES pi_tenant (id);

ALTER TABLE pi_tool_policy DROP CONSTRAINT uk_tool_policy;

ALTER TABLE pi_tool_policy
    ADD CONSTRAINT uk_tool_policy UNIQUE (tenant_id, scope, workspace_key, tool_name);
