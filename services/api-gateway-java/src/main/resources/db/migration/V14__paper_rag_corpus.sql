CREATE TABLE paper_corpus_docs (
  id             BIGINT PRIMARY KEY AUTO_INCREMENT,
  session_id     BIGINT NOT NULL,
  doc_uid        VARCHAR(64) NOT NULL,
  paper_id       VARCHAR(128) NOT NULL,
  title          VARCHAR(512) NOT NULL,
  year           INT,
  discipline     VARCHAR(128),
  doi            VARCHAR(256),
  url            VARCHAR(1024),
  language       VARCHAR(16) DEFAULT 'zh',
  source         VARCHAR(64) NOT NULL DEFAULT 'semantic_scholar',
  citation_count INT DEFAULT 0,
  chunk_count    INT DEFAULT 0,
  status         VARCHAR(32) NOT NULL DEFAULT 'pending',
  created_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_doc_uid (doc_uid),
  KEY idx_doc_session (session_id),
  CONSTRAINT fk_doc_session FOREIGN KEY (session_id) REFERENCES paper_topic_session(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE paper_corpus_chunks (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  doc_id      BIGINT NOT NULL,
  chunk_uid   VARCHAR(64) NOT NULL,
  chunk_index INT NOT NULL DEFAULT 0,
  chunk_type  VARCHAR(32) NOT NULL DEFAULT 'abstract',
  content     TEXT NOT NULL,
  token_count INT DEFAULT 0,
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_chunk_uid (chunk_uid),
  KEY idx_chunk_doc (doc_id),
  CONSTRAINT fk_chunk_doc FOREIGN KEY (doc_id) REFERENCES paper_corpus_docs(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
