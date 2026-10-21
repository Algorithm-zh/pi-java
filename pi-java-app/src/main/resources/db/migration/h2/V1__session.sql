-- M1：会话元数据与 append-only entry 日志（H2 MySQL 模式）。
-- 与 mysql/V1__session.sql 保持相同的版本号与语义，仅调整方言。

CREATE TABLE pi_session (
    id                 CHAR(26)      NOT NULL,
    parent_session_id  CHAR(26)      NULL,
    root_session_id    CHAR(26)      NOT NULL,
    fork_from_entry_id CHAR(26)      NULL,
    depth              SMALLINT      NOT NULL DEFAULT 0,
    origin             VARCHAR(16)   NOT NULL,
    title              VARCHAR(255)  NOT NULL,
    cwd                VARCHAR(1024) NOT NULL,
    model_profile_id   VARCHAR(64)   NOT NULL,
    thinking_level     VARCHAR(16)   NOT NULL DEFAULT 'OFF',
    sandbox_json       CLOB          NOT NULL,
    status             VARCHAR(16)   NOT NULL,
    leaf_entry_id      CHAR(26)      NULL,
    version            BIGINT        NOT NULL DEFAULT 0,
    created_at         TIMESTAMP(3)  NOT NULL,
    updated_at         TIMESTAMP(3)  NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_session_parent ON pi_session (parent_session_id);
CREATE INDEX idx_session_root ON pi_session (root_session_id);
CREATE INDEX idx_session_status ON pi_session (status, updated_at);

ALTER TABLE pi_session
    ADD CONSTRAINT fk_session_parent
    FOREIGN KEY (parent_session_id) REFERENCES pi_session (id)
    ON DELETE SET NULL;

CREATE TABLE pi_session_entry (
    id              CHAR(26)     NOT NULL,
    session_id      CHAR(26)     NOT NULL,
    seq             BIGINT       NOT NULL,
    parent_id       CHAR(26)     NULL,
    entry_type      VARCHAR(32)  NOT NULL,
    payload_version INT          NOT NULL,
    payload_json    CLOB         NOT NULL,
    token_estimate  INT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id)
);

CREATE UNIQUE INDEX uk_entry_session_seq ON pi_session_entry (session_id, seq);
CREATE INDEX idx_entry_session_parent ON pi_session_entry (session_id, parent_id);
CREATE INDEX idx_entry_session_type ON pi_session_entry (session_id, entry_type);

ALTER TABLE pi_session_entry
    ADD CONSTRAINT fk_entry_session
    FOREIGN KEY (session_id) REFERENCES pi_session (id)
    ON DELETE CASCADE;
