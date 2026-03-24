-- Step memory: persist step outputs for context recovery on retry/rebuild
CREATE TABLE task_step_memory (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id     VARCHAR(64)  NOT NULL,
    step_code   VARCHAR(64)  NOT NULL,
    memory_key  VARCHAR(128) NOT NULL,
    memory_value MEDIUMTEXT  NOT NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_task_step_key (task_id, step_code, memory_key),
    INDEX idx_task_id (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
