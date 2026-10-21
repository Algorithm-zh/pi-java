-- M3：工具策略规则（H2 MySQL 模式）。
-- 与 mysql/V3__tool_policy.sql 保持相同的版本号与语义，仅调整方言。

CREATE TABLE pi_tool_policy (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    scope         VARCHAR(16)  NOT NULL,
    workspace_key CHAR(64)     NOT NULL,
    tool_name     VARCHAR(128) NOT NULL,
    effect        VARCHAR(16)  NOT NULL,
    approval_mode VARCHAR(16)  NOT NULL,
    risk_level    VARCHAR(16)  NOT NULL,
    timeout_ms    INT          NULL,
    config_json   CLOB         NOT NULL,
    version       BIGINT       NOT NULL DEFAULT 1,
    created_at    TIMESTAMP(3) NOT NULL,
    updated_at    TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_tool_policy UNIQUE (scope, workspace_key, tool_name)
);
