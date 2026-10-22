-- M6：审批待办投影（MySQL 8）。
-- 该表不是审计事实源：终态决策必须同时追加 APPROVAL entry。
-- args_digest = sha256(canonicalJson(args))，用于校验展示参数与执行参数一致。

CREATE TABLE pi_approval_request (
    id                 CHAR(26)     NOT NULL,
    session_id         CHAR(26)     NOT NULL,
    run_id             CHAR(26)     NOT NULL,
    tool_call_id       VARCHAR(64)  NOT NULL,
    tool_name          VARCHAR(128) NOT NULL,
    args_digest        CHAR(64)     NOT NULL,
    args_redacted_json TEXT         NOT NULL,
    risk_level         VARCHAR(16)  NOT NULL,
    status             VARCHAR(16)  NOT NULL,
    reason             VARCHAR(1024) NULL,
    decided_by         VARCHAR(64)  NULL,
    decided_at         DATETIME(3)  NULL,
    expires_at         DATETIME(3)  NOT NULL,
    created_at         DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_approval_run_call (run_id, tool_call_id),
    KEY idx_approval_session (session_id, created_at),
    KEY idx_approval_status_expiry (status, expires_at),
    CONSTRAINT fk_approval_session FOREIGN KEY (session_id)
        REFERENCES pi_session (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
