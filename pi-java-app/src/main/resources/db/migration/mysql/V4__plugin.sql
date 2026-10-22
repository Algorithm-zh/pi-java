-- M5：插件安装记录（MySQL 8）。
-- 一个插件标识只有一行；jar 保存在磁盘，库中只记录路径与 SHA-256。
-- 启用时重新校验文件，安装后被替换的 jar 不会被加载。

CREATE TABLE pi_plugin (
    id           VARCHAR(128)  NOT NULL,
    version      VARCHAR(32)   NOT NULL,
    api_version  VARCHAR(16)   NOT NULL,
    jar_path     VARCHAR(1024) NOT NULL,
    checksum     CHAR(64)      NOT NULL,
    status       VARCHAR(16)   NOT NULL,
    config_json  TEXT          NOT NULL,
    last_error   VARCHAR(1024) NULL,
    installed_at DATETIME(3)   NOT NULL,
    updated_at   DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    KEY idx_plugin_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
