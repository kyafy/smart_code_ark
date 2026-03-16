CREATE TABLE ai_tasks (
    id VARCHAR(64) PRIMARY KEY,
    owner_id VARCHAR(64) NOT NULL,
    requirement VARCHAR(4000) NOT NULL,
    status VARCHAR(32) NOT NULL,
    result_json TEXT,
    error_message VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_ai_tasks_owner_created_at ON ai_tasks(owner_id, created_at DESC);
