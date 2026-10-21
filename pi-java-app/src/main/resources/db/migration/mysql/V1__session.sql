-- M1：会话元数据与 append-only entry 日志。
-- 约定：时间列存 UTC，精度毫秒；id 为 26 位 ULID。

CREATE TABLE pi_session (
    id                 CHAR(26)     NOT NULL,
    parent_session_id  CHAR(26)     NULL,
    root_session_id    CHAR(26)     NOT NULL,
    fork_from_entry_id CHAR(26)     NULL,
    depth              SMALLINT     NOT NULL DEFAULT 0,
    origin             VARCHAR(16)  NOT NULL,
    title              VARCHAR(255) NOT NULL,
    cwd                VARCHAR(1024) NOT NULL,
    model_profile_id   VARCHAR(64)  NOT NULL,
    thinking_level     VARCHAR(16)  NOT NULL DEFAULT 'OFF',
    sandbox_json       TEXT         NOT NULL,
    status             VARCHAR(16)  NOT NULL,
    leaf_entry_id      CHAR(26)     NULL,
    version            BIGINT       NOT NULL DEFAULT 0,
    created_at         DATETIME(3)  NOT NULL,
    updated_at         DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_session_parent (parent_session_id),
    KEY idx_session_root (root_session_id),
    KEY idx_session_status (status, updated_at),
    CONSTRAINT fk_session_parent
        FOREIGN KEY (parent_session_id) REFERENCES pi_session (id)
        ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE pi_session_entry (
    id              CHAR(26)    NOT NULL,
    session_id      CHAR(26)    NOT NULL,
    seq             BIGINT      NOT NULL,
    parent_id       CHAR(26)    NULL,
    entry_type      VARCHAR(32) NOT NULL,
    payload_version INT         NOT NULL,
    payload_json    LONGTEXT    NOT NULL,
    token_estimate  INT         NOT NULL DEFAULT 0,
    created_at      DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_entry_session_seq (session_id, seq),
    KEY idx_entry_session_parent (session_id, parent_id),
    KEY idx_entry_session_type (session_id, entry_type),
    CONSTRAINT fk_entry_session
        FOREIGN KEY (session_id) REFERENCES pi_session (id)
        ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
