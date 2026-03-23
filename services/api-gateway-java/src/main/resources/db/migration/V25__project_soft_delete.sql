ALTER TABLE projects
  ADD COLUMN deleted_at DATETIME NULL AFTER updated_at;

ALTER TABLE projects
  ADD KEY idx_projects_user_deleted_updated (user_id, deleted_at, updated_at);

