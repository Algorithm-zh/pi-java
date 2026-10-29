-- M20：空间软删除。deleted_at 非空表示已归档，不参与登录定位与后台任务。

ALTER TABLE pi_tenant ADD COLUMN deleted_at DATETIME(3) NULL;

CREATE INDEX idx_tenant_deleted ON pi_tenant (deleted_at);
