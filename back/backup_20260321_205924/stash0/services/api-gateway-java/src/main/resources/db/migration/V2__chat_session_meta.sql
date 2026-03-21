ALTER TABLE chat_sessions
  ADD COLUMN project_type VARCHAR(32) NOT NULL DEFAULT 'web' AFTER title,
  ADD COLUMN description VARCHAR(1000) NULL AFTER project_type;
