-- M5：插件安装记录（H2 MySQL 模式）。
-- 与 mysql/V4__plugin.sql 保持相同的版本号与语义，仅调整方言。
-- jar 不复制进库；jar_path 与 checksum 用于重启后重新验证文件。

CREATE TABLE pi_plugin (
    id           VARCHAR(128)  NOT NULL,
    version      VARCHAR(32)   NOT NULL,
    api_version  VARCHAR(16)   NOT NULL,
    jar_path     VARCHAR(1024) NOT NULL,
    checksum     CHAR(64)      NOT NULL,
    status       VARCHAR(16)   NOT NULL,
    config_json  CLOB          NOT NULL,
    last_error   VARCHAR(1024) NULL,
    installed_at TIMESTAMP(3)  NOT NULL,
    updated_at   TIMESTAMP(3)  NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_plugin_status ON pi_plugin (status);
