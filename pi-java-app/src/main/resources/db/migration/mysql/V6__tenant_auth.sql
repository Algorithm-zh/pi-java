-- M19：租户、本地账号与访问令牌（MySQL 8）。
-- 默认租户使用固定 ID，存量会话与策略在升级时自动归入该租户。

CREATE TABLE pi_tenant (
    id         CHAR(26)     NOT NULL,
    slug       VARCHAR(64)  NOT NULL,
    name       VARCHAR(128) NOT NULL,
    created_at DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_slug (slug)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

INSERT INTO pi_tenant (id, slug, name, created_at)
VALUES ('00000000000000000000000001', 'default', '默认空间', CURRENT_TIMESTAMP(3));

CREATE TABLE pi_app_user (
    id            CHAR(26)     NOT NULL,
    tenant_id     CHAR(26)     NOT NULL,
    username      VARCHAR(64)  NOT NULL,
    display_name  VARCHAR(128) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(16)  NOT NULL,
    status        VARCHAR(16)  NOT NULL,
    created_at    DATETIME(3)  NOT NULL,
    updated_at    DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_tenant_username (tenant_id, username),
    KEY idx_user_tenant (tenant_id),
    CONSTRAINT fk_user_tenant FOREIGN KEY (tenant_id) REFERENCES pi_tenant (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE pi_auth_token (
    id           CHAR(26)    NOT NULL,
    tenant_id    CHAR(26)    NOT NULL,
    user_id      CHAR(26)    NOT NULL,
    token_hash   CHAR(64)    NOT NULL,
    issued_at    DATETIME(3) NOT NULL,
    expires_at   DATETIME(3) NOT NULL,
    last_used_at DATETIME(3) NULL,
    revoked_at   DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_auth_token_hash (token_hash),
    KEY idx_auth_token_user (user_id),
    KEY idx_auth_token_expiry (expires_at),
    CONSTRAINT fk_auth_token_tenant FOREIGN KEY (tenant_id) REFERENCES pi_tenant (id),
    CONSTRAINT fk_auth_token_user FOREIGN KEY (user_id) REFERENCES pi_app_user (id)
        ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

ALTER TABLE pi_session
    ADD COLUMN tenant_id CHAR(26) NOT NULL DEFAULT '00000000000000000000000001',
    ADD KEY idx_session_tenant_updated (tenant_id, updated_at),
    ADD CONSTRAINT fk_session_tenant FOREIGN KEY (tenant_id) REFERENCES pi_tenant (id);

ALTER TABLE pi_tool_policy
    ADD COLUMN tenant_id CHAR(26) NOT NULL DEFAULT '00000000000000000000000001',
    ADD CONSTRAINT fk_policy_tenant FOREIGN KEY (tenant_id) REFERENCES pi_tenant (id);

ALTER TABLE pi_tool_policy DROP INDEX uk_tool_policy;

ALTER TABLE pi_tool_policy
    ADD UNIQUE KEY uk_tool_policy (tenant_id, scope, workspace_key, tool_name);
