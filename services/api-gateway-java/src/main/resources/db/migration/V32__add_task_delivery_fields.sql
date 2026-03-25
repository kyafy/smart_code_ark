ALTER TABLE tasks
    ADD COLUMN delivery_level_requested VARCHAR(32) NULL AFTER instructions,
    ADD COLUMN delivery_level_actual VARCHAR(32) NULL AFTER delivery_level_requested,
    ADD COLUMN delivery_status VARCHAR(32) NULL AFTER delivery_level_actual,
    ADD COLUMN template_id VARCHAR(128) NULL AFTER delivery_status;

UPDATE tasks
SET delivery_level_requested = COALESCE(delivery_level_requested, 'draft'),
    delivery_level_actual = COALESCE(delivery_level_actual, 'draft'),
    delivery_status = COALESCE(delivery_status, 'pending')
WHERE delivery_level_requested IS NULL
   OR delivery_level_actual IS NULL
   OR delivery_status IS NULL;
