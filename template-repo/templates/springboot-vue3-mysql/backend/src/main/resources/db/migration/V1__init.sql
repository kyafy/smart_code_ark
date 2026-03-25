CREATE TABLE IF NOT EXISTS users (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(64) NOT NULL,
  email VARCHAR(128) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_users_email (email)
);

INSERT INTO users (name, email)
VALUES ('Template Admin', 'admin@example.com')
ON DUPLICATE KEY UPDATE name = VALUES(name);
