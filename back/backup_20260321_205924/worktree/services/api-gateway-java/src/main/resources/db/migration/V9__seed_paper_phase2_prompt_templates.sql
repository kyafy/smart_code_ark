INSERT INTO prompt_templates (
  template_key, name, scene, description, status, default_version_no,
  cache_enabled, cache_ttl_seconds, created_by
)
SELECT
  'paper_outline_expand', '论文大纲扩写模板', 'paper_outline', '用于将大纲扩展为可读章节文稿', 'published', 1,
  0, 0, NULL
WHERE NOT EXISTS (
  SELECT 1 FROM prompt_templates WHERE template_key = 'paper_outline_expand'
);

INSERT INTO prompt_templates (
  template_key, name, scene, description, status, default_version_no,
  cache_enabled, cache_ttl_seconds, created_by
)
SELECT
  'paper_outline_quality_rewrite', '论文质量回写模板', 'paper_outline', '用于根据质检问题回写文稿', 'published', 1,
  0, 0, NULL
WHERE NOT EXISTS (
  SELECT 1 FROM prompt_templates WHERE template_key = 'paper_outline_quality_rewrite'
);

INSERT INTO prompt_versions (
  template_id, version_no, system_prompt, user_prompt, output_schema_json,
  model, temperature, top_p, status, published_by, published_at
)
SELECT
  t.id,
  1,
  '你是论文写作助手。请基于输入的大纲与文献，扩展为可读文稿，输出 JSON：{topic:string,topicRefined:string,researchQuestions:string[],chapters:[{index:number,title:string,summary:string,sections:[{title:string,content:string,citations:string[]}]}]}。仅输出 JSON。',
  '主题：{{topic}}\n细化题目：{{topicRefined}}\n学科：{{discipline}}\n学位层次：{{degreeLevel}}\n方法偏好：{{methodPreference}}\n研究问题：{{researchQuestions}}\n大纲：{{outlineJson}}\n候选文献：{{sources}}',
  NULL,
  'qwen-plus',
  0.20,
  0.90,
  'published',
  NULL,
  CURRENT_TIMESTAMP
FROM prompt_templates t
WHERE t.template_key = 'paper_outline_expand'
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
  '你是论文改写助手。请根据质检问题修订文稿，输出 JSON：{manuscript:{...},appliedIssues:string[],summary:string}。仅输出 JSON。',
  '主题：{{topic}}\n细化题目：{{topicRefined}}\n引用规范：{{citationStyle}}\n质检报告：{{qualityReportJson}}\n当前文稿：{{manuscriptJson}}',
  NULL,
  'qwen-plus',
  0.20,
  0.90,
  'published',
  NULL,
  CURRENT_TIMESTAMP
FROM prompt_templates t
WHERE t.template_key = 'paper_outline_quality_rewrite'
  AND NOT EXISTS (
    SELECT 1 FROM prompt_versions v WHERE v.template_id = t.id AND v.version_no = 1
  );
