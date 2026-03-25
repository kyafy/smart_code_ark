ALTER TABLE model_registry
    ADD COLUMN base_url VARCHAR(255) NULL AFTER enabled,
    ADD COLUMN api_key_ciphertext VARCHAR(4096) NULL AFTER base_url,
    ADD COLUMN api_key_masked VARCHAR(64) NULL AFTER api_key_ciphertext,
    ADD COLUMN crypto_version VARCHAR(16) NULL AFTER api_key_masked;
