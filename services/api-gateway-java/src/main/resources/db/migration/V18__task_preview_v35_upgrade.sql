-- PRD v3.5: 预览重构 - 新增 phase 阶段追踪、构建日志、结构化错误码、健康检查时间
ALTER TABLE task_preview ADD COLUMN phase VARCHAR(32) NULL AFTER status;
ALTER TABLE task_preview ADD COLUMN build_log_url VARCHAR(512) NULL AFTER runtime_id;
ALTER TABLE task_preview ADD COLUMN last_error_code INT NULL AFTER last_error;
ALTER TABLE task_preview ADD COLUMN last_health_check_at DATETIME NULL AFTER last_error_code;
CREATE INDEX idx_status_updated ON task_preview(status, updated_at);
