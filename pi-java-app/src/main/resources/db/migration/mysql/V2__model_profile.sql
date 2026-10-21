-- M2：模型档案（MySQL 8）。
-- 只保存 API Key 的环境变量名，禁止在该表存放密钥明文。

CREATE TABLE pi_model_profile (
    id                  VARCHAR(64)  NOT NULL,
    display_name        VARCHAR(128) NOT NULL,
    provider            VARCHAR(32)  NOT NULL,
    base_url            VARCHAR(512) NOT NULL DEFAULT '',
    api_key_env         VARCHAR(128) NOT NULL DEFAULT '',
    model_name          VARCHAR(128) NOT NULL,
    context_window      INT          NOT NULL,
    max_output_tokens   INT          NOT NULL,
    capabilities_json   TEXT         NOT NULL,
    enabled             BOOLEAN      NOT NULL DEFAULT TRUE,
    version             BIGINT       NOT NULL DEFAULT 1,
    created_at          DATETIME(3)  NOT NULL,
    updated_at          DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_model_profile_enabled (enabled)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
