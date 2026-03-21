CREATE TABLE IF NOT EXISTS task_preview (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  task_id VARCHAR(64) NOT NULL,
  project_id VARCHAR(64) NOT NULL,
  user_id BIGINT NOT NULL,
  status VARCHAR(32) NOT NULL,
  preview_url VARCHAR(512) NULL,
  runtime_id VARCHAR(128) NULL,
  expire_at DATETIME NULL,
  last_error VARCHAR(1024) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_task_id (task_id),
  KEY idx_user_status (user_id, status),
  KEY idx_expire_at (expire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
