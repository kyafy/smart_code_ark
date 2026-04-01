ALTER TABLE tasks
    ADD COLUMN codegen_engine VARCHAR(32) NULL AFTER template_id,
    ADD COLUMN deploy_mode VARCHAR(32) NULL AFTER codegen_engine,
    ADD COLUMN deploy_env VARCHAR(32) NULL AFTER deploy_mode,
    ADD COLUMN strict_delivery TINYINT(1) NULL AFTER deploy_env,
    ADD COLUMN auto_build_image TINYINT(1) NULL AFTER strict_delivery,
    ADD COLUMN auto_push_image TINYINT(1) NULL AFTER auto_build_image,
    ADD COLUMN auto_deploy_target TINYINT(1) NULL AFTER auto_push_image,
    ADD COLUMN release_status VARCHAR(32) NULL AFTER auto_deploy_target;

UPDATE tasks
SET codegen_engine = COALESCE(codegen_engine, 'llm'),
    deploy_mode = COALESCE(deploy_mode, 'none'),
    deploy_env = COALESCE(deploy_env, 'local'),
    strict_delivery = COALESCE(strict_delivery, 0),
    auto_build_image = COALESCE(auto_build_image, 0),
    auto_push_image = COALESCE(auto_push_image, 0),
    auto_deploy_target = COALESCE(auto_deploy_target, 0),
    release_status = COALESCE(release_status, 'pending')
WHERE codegen_engine IS NULL
   OR deploy_mode IS NULL
   OR deploy_env IS NULL
   OR strict_delivery IS NULL
   OR auto_build_image IS NULL
   OR auto_push_image IS NULL
   OR auto_deploy_target IS NULL
   OR release_status IS NULL;
