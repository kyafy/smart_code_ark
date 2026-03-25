INSERT INTO prompt_templates (
  template_key, name, scene, description, status, default_version_no,
  cache_enabled, cache_ttl_seconds, created_by
)
SELECT
  'project_structure_plan', '项目结构规划模板', 'agent_codegen', '用于生成结构化项目文件清单', 'published', 1,
  0, 0, NULL
WHERE NOT EXISTS (
  SELECT 1 FROM prompt_templates WHERE template_key = 'project_structure_plan'
);

INSERT INTO prompt_templates (
  template_key, name, scene, description, status, default_version_no,
  cache_enabled, cache_ttl_seconds, created_by
)
SELECT
  'file_content_generate', '文件内容生成模板', 'agent_codegen', '用于按文件生成代码内容', 'published', 1,
  0, 0, NULL
WHERE NOT EXISTS (
  SELECT 1 FROM prompt_templates WHERE template_key = 'file_content_generate'
);

INSERT INTO prompt_versions (
  template_id, version_no, system_prompt, user_prompt, output_schema_json,
  model, temperature, top_p, status, published_by, published_at
)
SELECT
  t.id,
  1,
  '你是一个资深架构师。请根据以下PRD和技术栈，规划项目文件结构。技术栈：后端 {{stackBackend}}，前端 {{stackFrontend}}，数据库 {{stackDb}}。输出 JSON 数组，元素是相对路径字符串；只输出 JSON，不要 Markdown。',
  'PRD内容：\n{{prd}}\n\n额外指令：\n{{instructions}}',
  NULL,
  'qwen-plus',
  0.10,
  0.90,
  'published',
  NULL,
  CURRENT_TIMESTAMP
FROM prompt_templates t
WHERE t.template_key = 'project_structure_plan'
  AND NOT EXISTS (
    SELECT 1 FROM prompt_versions v WHERE v.template_id = t.id AND v.version_no = 1
  );

INSERT INTO prompt_versions (
  template_id, version_no, system_prompt, user_prompt, output_schema_json,
  model, temperature, top_p, status, published_by, published_at
)
SELECT
  t.id,
  1,
  '你是一个全栈工程师。请生成目标文件完整内容。文件路径：{{filePath}}；技术栈：{{techStack}}。输出纯文件内容，不要 Markdown 代码块包装。',
  'PRD内容：\n{{prd}}\n\n额外指令：\n{{instructions}}',
  NULL,
  'qwen-plus',
  0.20,
  0.90,
  'published',
  NULL,
  CURRENT_TIMESTAMP
FROM prompt_templates t
WHERE t.template_key = 'file_content_generate'
  AND NOT EXISTS (
    SELECT 1 FROM prompt_versions v WHERE v.template_id = t.id AND v.version_no = 1
  );
