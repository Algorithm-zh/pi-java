-- M6：审批待办投影（H2 MySQL 模式）。
-- 与 mysql/V5__approval_request.sql 保持相同版本号与语义，仅调整方言。
-- 该表不是审计事实源：终态决策必须同时追加 APPROVAL entry。

CREATE TABLE pi_approval_request (
    id                 CHAR(26)     NOT NULL,
    session_id         CHAR(26)     NOT NULL,
    run_id             CHAR(26)     NOT NULL,
    tool_call_id       VARCHAR(64)  NOT NULL,
    tool_name          VARCHAR(128) NOT NULL,
    args_digest        CHAR(64)     NOT NULL,
    args_redacted_json CLOB         NOT NULL,
    risk_level         VARCHAR(16)  NOT NULL,
    status             VARCHAR(16)  NOT NULL,
    reason             VARCHAR(1024) NULL,
    decided_by         VARCHAR(64)  NULL,
    decided_at         TIMESTAMP(3) NULL,
    expires_at         TIMESTAMP(3) NOT NULL,
    created_at         TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_approval_run_call UNIQUE (run_id, tool_call_id),
    CONSTRAINT fk_approval_session FOREIGN KEY (session_id)
        REFERENCES pi_session (id) ON DELETE CASCADE
);

CREATE INDEX idx_approval_session ON pi_approval_request (session_id, created_at);
CREATE INDEX idx_approval_status_expiry ON pi_approval_request (status, expires_at);
