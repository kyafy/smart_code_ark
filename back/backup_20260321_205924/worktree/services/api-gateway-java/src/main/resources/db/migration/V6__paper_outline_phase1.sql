CREATE TABLE IF NOT EXISTS paper_topic_session (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  task_id VARCHAR(64) NOT NULL,
  project_id VARCHAR(64) NULL,
  user_id BIGINT NOT NULL,
  topic VARCHAR(512) NOT NULL,
  discipline VARCHAR(128) NOT NULL,
  degree_level VARCHAR(64) NOT NULL,
  method_preference VARCHAR(64) NULL,
  status VARCHAR(32) NOT NULL,
  topic_refined TEXT NULL,
  research_questions_json JSON NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_paper_topic_task (task_id),
  KEY idx_paper_topic_user_created (user_id, created_at),
  KEY idx_paper_topic_project_created (project_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS paper_sources (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  session_id BIGINT NOT NULL,
  section_key VARCHAR(128) NULL,
  paper_id VARCHAR(128) NOT NULL,
  title VARCHAR(512) NOT NULL,
  authors_json JSON NULL,
  year INT NULL,
  venue VARCHAR(255) NULL,
  url VARCHAR(1024) NULL,
  abstract_text TEXT NULL,
  evidence_snippet TEXT NULL,
  relevance_score DECIMAL(5,4) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_paper_sources_session (session_id, created_at),
  KEY idx_paper_sources_paper (paper_id),
  CONSTRAINT fk_paper_sources_session FOREIGN KEY (session_id) REFERENCES paper_topic_session(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS paper_outline_versions (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  session_id BIGINT NOT NULL,
  version_no INT NOT NULL,
  citation_style VARCHAR(32) NOT NULL DEFAULT 'GB/T 7714',
  outline_json JSON NOT NULL,
  quality_report_json JSON NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_paper_outline_session_version (session_id, version_no),
  KEY idx_paper_outline_session_created (session_id, created_at),
  CONSTRAINT fk_paper_outline_session FOREIGN KEY (session_id) REFERENCES paper_topic_session(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
