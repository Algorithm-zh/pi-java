-- M3：工具策略规则（MySQL 8）。
-- workspace_key = sha256(规范化 cwd)，避免用长路径做唯一索引。
-- 内置工具的默认风险在代码里，本表只存覆盖规则。

CREATE TABLE pi_tool_policy (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    scope         VARCHAR(16)  NOT NULL,
    workspace_key CHAR(64)     NOT NULL,
    tool_name     VARCHAR(128) NOT NULL,
    effect        VARCHAR(16)  NOT NULL,
    approval_mode VARCHAR(16)  NOT NULL,
    risk_level    VARCHAR(16)  NOT NULL,
    timeout_ms    INT          NULL,
    config_json   TEXT         NOT NULL,
    version       BIGINT       NOT NULL DEFAULT 1,
    created_at    DATETIME(3)  NOT NULL,
    updated_at    DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tool_policy (scope, workspace_key, tool_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
