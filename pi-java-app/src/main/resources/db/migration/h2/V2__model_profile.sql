-- M2：模型档案（H2 MySQL 模式）。
-- 与 mysql/V2__model_profile.sql 保持相同的版本号与语义，仅调整方言。

CREATE TABLE pi_model_profile (
    id                  VARCHAR(64)  NOT NULL,
    display_name        VARCHAR(128) NOT NULL,
    provider            VARCHAR(32)  NOT NULL,
    base_url            VARCHAR(512) NOT NULL DEFAULT '',
    api_key_env         VARCHAR(128) NOT NULL DEFAULT '',
    model_name          VARCHAR(128) NOT NULL,
    context_window      INT          NOT NULL,
    max_output_tokens   INT          NOT NULL,
    capabilities_json   CLOB         NOT NULL,
    enabled             BOOLEAN      NOT NULL DEFAULT TRUE,
    version             BIGINT       NOT NULL DEFAULT 1,
    created_at          TIMESTAMP(3) NOT NULL,
    updated_at          TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_model_profile_enabled ON pi_model_profile (enabled);
