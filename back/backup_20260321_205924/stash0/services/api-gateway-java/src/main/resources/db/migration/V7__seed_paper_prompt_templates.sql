INSERT INTO prompt_templates (
  template_key, name, scene, description, status, default_version_no,
  cache_enabled, cache_ttl_seconds, created_by
)
SELECT
  'paper_topic_clarify', '论文主题澄清模板', 'paper_outline', '用于论文题目细化与研究问题提炼', 'published', 1,
  0, 0, NULL
WHERE NOT EXISTS (
  SELECT 1 FROM prompt_templates WHERE template_key = 'paper_topic_clarify'
);

INSERT INTO prompt_templates (
  template_key, name, scene, description, status, default_version_no,
  cache_enabled, cache_ttl_seconds, created_by
)
SELECT
  'paper_outline_generate', '论文大纲生成模板', 'paper_outline', '用于生成三级论文大纲与证据线索', 'published', 1,
  0, 0, NULL
WHERE NOT EXISTS (
  SELECT 1 FROM prompt_templates WHERE template_key = 'paper_outline_generate'
);

INSERT INTO prompt_templates (
  template_key, name, scene, description, status, default_version_no,
  cache_enabled, cache_ttl_seconds, created_by
)
SELECT
  'paper_outline_quality_check', '论文大纲质检模板', 'paper_outline', '用于校验论文大纲逻辑与引用可核验性', 'published', 1,
  0, 0, NULL
WHERE NOT EXISTS (
  SELECT 1 FROM prompt_templates WHERE template_key = 'paper_outline_quality_check'
);

INSERT INTO prompt_versions (
  template_id, version_no, system_prompt, user_prompt, output_schema_json,
  model, temperature, top_p, status, published_by, published_at
)
SELECT
  t.id,
  1,
  '你是论文导师助手。请将输入主题细化为可执行毕业论文题目，并输出 JSON：{topicRefined:string,researchQuestions:string[]}，仅输出 JSON。',
  '主题：{{topic}}\n学科：{{discipline}}\n学位层次：{{degreeLevel}}\n方法偏好：{{methodPreference}}',
  NULL,
  'qwen-plus',
  0.20,
  0.90,
  'published',
  NULL,
  CURRENT_TIMESTAMP
FROM prompt_templates t
WHERE t.template_key = 'paper_topic_clarify'
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
  '你是论文结构专家。请基于主题、研究问题和候选文献，输出 JSON 对象：{researchQuestions:string[],chapters:[],references:[]}。chapters 必须为章-节-小节三级结构；每个小节给 3 条 evidence，evidence 含 paperId/title/url。',
  '主题：{{topic}}\n细化题目：{{topicRefined}}\n学科：{{discipline}}\n学位层次：{{degreeLevel}}\n方法偏好：{{methodPreference}}\n研究问题：{{researchQuestions}}\n候选文献：{{sources}}',
  NULL,
  'qwen-plus',
  0.20,
  0.90,
  'published',
  NULL,
  CURRENT_TIMESTAMP
FROM prompt_templates t
WHERE t.template_key = 'paper_outline_generate'
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
  '你是论文质检助手。请对大纲做逻辑闭环、问题-方法一致性、引用可核验性检查，输出 JSON：{logicClosedLoop:boolean,methodConsistency:string,citationVerifiability:string,issues:string[]}。',
  '主题：{{topic}}\n细化题目：{{topicRefined}}\n引文样式：{{citationStyle}}\n大纲：{{outlineJson}}',
  NULL,
  'qwen-plus',
  0.10,
  0.90,
  'published',
  NULL,
  CURRENT_TIMESTAMP
FROM prompt_templates t
WHERE t.template_key = 'paper_outline_quality_check'
  AND NOT EXISTS (
    SELECT 1 FROM prompt_versions v WHERE v.template_id = t.id AND v.version_no = 1
  );
