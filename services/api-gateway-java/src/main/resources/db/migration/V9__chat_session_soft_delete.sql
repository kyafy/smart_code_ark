ALTER TABLE chat_sessions
    ADD COLUMN deleted_at DATETIME NULL AFTER updated_at;

CREATE INDEX idx_chat_sessions_user_status_updated
    ON chat_sessions(user_id, status, updated_at);
