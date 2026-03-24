-- Model registry: tracks available models and their daily token limits
CREATE TABLE model_registry (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    model_name  VARCHAR(64)  NOT NULL COMMENT 'model identifier on the platform, e.g. qwen-plus',
    display_name VARCHAR(128) NOT NULL DEFAULT '' COMMENT 'human-readable name',
    provider    VARCHAR(64)  NOT NULL DEFAULT 'dashscope' COMMENT 'platform provider',
    model_role  VARCHAR(32)  NOT NULL DEFAULT 'code' COMMENT 'chat or code',
    daily_token_limit BIGINT NOT NULL DEFAULT 0 COMMENT 'daily token limit (input+output), 0 means unlimited',
    priority    INT          NOT NULL DEFAULT 100 COMMENT 'lower value = higher priority for auto-switch',
    enabled     TINYINT(1)   NOT NULL DEFAULT 1,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_model_name (model_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Model registry for routing';

-- Daily usage aggregation per model
CREATE TABLE model_usage_daily (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    model_name  VARCHAR(64)  NOT NULL,
    usage_date  DATE         NOT NULL,
    call_count  BIGINT       NOT NULL DEFAULT 0,
    token_input BIGINT       NOT NULL DEFAULT 0,
    token_output BIGINT      NOT NULL DEFAULT 0,
    token_total BIGINT       NOT NULL DEFAULT 0 COMMENT 'input + output',
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_model_date (model_name, usage_date),
    INDEX idx_usage_date (usage_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Daily model usage aggregation';
