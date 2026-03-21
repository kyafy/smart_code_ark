CREATE TABLE IF NOT EXISTS users (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  username VARCHAR(64) NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  role VARCHAR(32) NOT NULL,
  balance DECIMAL(10,2) NOT NULL DEFAULT 0.00,
  quota INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_users_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS chat_sessions (
  id VARCHAR(64) PRIMARY KEY,
  user_id BIGINT NOT NULL,
  project_id VARCHAR(64) NULL,
  title VARCHAR(128) NOT NULL,
  status VARCHAR(32) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_chat_sessions_user_created (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS chat_messages (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  session_id VARCHAR(64) NOT NULL,
  speaker VARCHAR(16) NOT NULL,
  message TEXT NOT NULL,
  token_used INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_session_created (session_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS projects (
  id VARCHAR(64) PRIMARY KEY,
  user_id BIGINT NOT NULL,
  title VARCHAR(128) NOT NULL,
  description VARCHAR(1000) NOT NULL DEFAULT '',
  project_type VARCHAR(32) NOT NULL,
  stack_backend VARCHAR(32) NULL,
  stack_frontend VARCHAR(32) NULL,
  stack_db VARCHAR(32) NULL,
  status VARCHAR(32) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_projects_user_updated (user_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS project_specs (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  project_id VARCHAR(64) NOT NULL,
  version INT NOT NULL,
  requirement_json JSON NULL,
  domain_json JSON NULL,
  api_contract_json JSON NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_project_version (project_id, version),
  KEY idx_project_specs_project_created (project_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tasks (
  id VARCHAR(64) PRIMARY KEY,
  project_id VARCHAR(64) NOT NULL,
  user_id BIGINT NOT NULL,
  task_type VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL,
  progress INT NOT NULL DEFAULT 0,
  current_step VARCHAR(64) NULL,
  error_code VARCHAR(16) NULL,
  error_message VARCHAR(255) NULL,
  result_url VARCHAR(512) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_tasks_user_created (user_id, created_at),
  KEY idx_tasks_project_updated (project_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS task_steps (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  task_id VARCHAR(64) NOT NULL,
  step_code VARCHAR(64) NOT NULL,
  step_name VARCHAR(128) NOT NULL,
  step_order INT NOT NULL,
  status VARCHAR(32) NOT NULL,
  progress INT NOT NULL DEFAULT 0,
  started_at DATETIME NULL,
  finished_at DATETIME NULL,
  error_code VARCHAR(16) NULL,
  error_message VARCHAR(255) NULL,
  retry_count INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_task_step_order (task_id, step_order),
  UNIQUE KEY uk_task_step (task_id, step_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS task_logs (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  task_id VARCHAR(64) NOT NULL,
  level VARCHAR(16) NOT NULL,
  content TEXT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_task_created (task_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS artifacts (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  task_id VARCHAR(64) NOT NULL,
  project_id VARCHAR(64) NOT NULL,
  artifact_type VARCHAR(32) NOT NULL,
  storage_url VARCHAR(512) NOT NULL,
  size_bytes BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_artifacts_task (task_id, created_at),
  KEY idx_artifacts_project (project_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS billing_records (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  project_id VARCHAR(64) NULL,
  task_id VARCHAR(64) NULL,
  change_amount DECIMAL(10,2) NOT NULL,
  currency VARCHAR(8) NOT NULL DEFAULT 'CNY',
  reason VARCHAR(64) NOT NULL,
  balance_after DECIMAL(10,2) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_user_created (user_id, created_at),
  KEY idx_task_created_billing (task_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS prompt_templates (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  template_key VARCHAR(64) NOT NULL,
  name VARCHAR(128) NOT NULL,
  scene VARCHAR(64) NOT NULL,
  description VARCHAR(512) NULL,
  status VARCHAR(32) NOT NULL,
  default_version_no INT NOT NULL DEFAULT 1,
  cache_enabled TINYINT(1) NOT NULL DEFAULT 0,
  cache_ttl_seconds INT NOT NULL DEFAULT 0,
  created_by BIGINT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_template_key (template_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS prompt_versions (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  template_id BIGINT NOT NULL,
  version_no INT NOT NULL,
  system_prompt TEXT NULL,
  user_prompt TEXT NULL,
  output_schema_json JSON NULL,
  model VARCHAR(64) NOT NULL,
  temperature DECIMAL(3,2) NULL,
  top_p DECIMAL(3,2) NULL,
  status VARCHAR(32) NOT NULL,
  published_by BIGINT NULL,
  published_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_template_version (template_id, version_no),
  KEY idx_prompt_versions_template (template_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS prompt_history (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  task_id VARCHAR(64) NULL,
  project_id VARCHAR(64) NULL,
  template_key VARCHAR(64) NOT NULL,
  version_no INT NOT NULL,
  model VARCHAR(64) NOT NULL,
  request_hash VARCHAR(128) NOT NULL,
  input_json JSON NULL,
  output_json JSON NULL,
  token_input INT NOT NULL DEFAULT 0,
  token_output INT NOT NULL DEFAULT 0,
  latency_ms INT NOT NULL DEFAULT 0,
  status VARCHAR(32) NOT NULL,
  error_code VARCHAR(16) NULL,
  error_message VARCHAR(255) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_task_template (task_id, template_key),
  KEY idx_project_created (project_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS prompt_cache (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  cache_key VARCHAR(128) NOT NULL,
  template_key VARCHAR(64) NOT NULL,
  version_no INT NOT NULL,
  model VARCHAR(64) NOT NULL,
  request_hash VARCHAR(128) NOT NULL,
  response_json JSON NULL,
  hit_count INT NOT NULL DEFAULT 0,
  expires_at DATETIME NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_cache_key (cache_key),
  KEY idx_template_version (template_key, version_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
