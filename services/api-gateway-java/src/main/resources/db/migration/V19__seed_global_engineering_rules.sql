-- Global engineering rules prompt template
INSERT INTO prompt_templates (
  template_key, name, scene, description, status, default_version_no,
  cache_enabled, cache_ttl_seconds, created_by
)
SELECT
  'global_engineering_rules', '全局工程规范', 'agent_codegen',
  '注入到所有代码生成 LLM 调用中的全局工程约束规则', 'published', 1,
  0, 0, NULL
WHERE NOT EXISTS (
  SELECT 1 FROM prompt_templates WHERE template_key = 'global_engineering_rules'
);

INSERT INTO prompt_versions (
  template_id, version_no, system_prompt, user_prompt, output_schema_json,
  model, temperature, top_p, status, published_by, published_at
)
SELECT
  t.id,
  1,
  '## 全局工程规范（所有生成文件必须遵守）
1. 文件编码统一 UTF-8，换行符 LF。
2. 禁止绝对路径，所有路径必须为相对路径，禁止包含 ".."。
3. Java 后端必须包含 pom.xml、mvnw、mvnw.cmd（Maven Wrapper），确保可独立构建。
4. 前端项目必须包含 package.json，且含 dev、build、preview 三个 script。
5. docker-compose.yml 的 build.context 必须指向实际存在的相对目录。
6. docker-compose.yml 中每个服务必须包含 healthcheck 配置。
7. 每个 Controller 必须有参数校验与错误处理，禁止裸抛异常。
8. 禁止 TODO、FIXME、占位符代码，必须实现完整业务逻辑。
9. SQL migration 文件必须使用 Flyway 命名规范：V{n}__description.sql。
10. 启动脚本（start.sh / deploy.sh）必须设置为可执行，并包含健康检查等待逻辑。',
  NULL,
  NULL,
  'qwen-plus',
  NULL,
  NULL,
  'published',
  NULL,
  CURRENT_TIMESTAMP
FROM prompt_templates t
WHERE t.template_key = 'global_engineering_rules'
  AND NOT EXISTS (
    SELECT 1 FROM prompt_versions WHERE template_id = t.id AND version_no = 1
  );
