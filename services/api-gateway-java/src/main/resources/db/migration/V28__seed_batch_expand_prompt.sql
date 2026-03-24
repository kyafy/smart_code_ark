SET @template_id = (SELECT id FROM prompt_templates WHERE template_key = 'paper_outline_expand_batch' LIMIT 1);

INSERT INTO prompt_templates (
  template_key,
  name,
  scene,
  description,
  status,
  default_version_no,
  cache_enabled,
  cache_ttl_seconds,
  created_by,
  created_at,
  updated_at
)
SELECT
  'paper_outline_expand_batch',
  '论文分批扩写',
  'paper',
  '论文扩写分批模板',
  'active',
  1,
  0,
  0,
  NULL,
  CURRENT_TIMESTAMP,
  CURRENT_TIMESTAMP
WHERE @template_id IS NULL;

SET @template_id = (SELECT id FROM prompt_templates WHERE template_key = 'paper_outline_expand_batch' LIMIT 1);

INSERT INTO prompt_versions (
  template_id,
  version_no,
  system_prompt,
  user_prompt,
  output_schema_json,
  temperature,
  top_p,
  model,
  status,
  published_by,
  published_at,
  created_at
)
SELECT
  @template_id,
  1,
  '你是严谨的论文写作助手。你将只扩写论文的一部分章节。必须仅输出合法 JSON，不得输出 Markdown。禁止占位文本（placeholder/TBD/待补充/暂无正文）。',
  '全局主题：{{topic}}\n细化题目：{{topicRefined}}\n学科：{{discipline}}\n学位层次：{{degreeLevel}}\n方法偏好：{{methodPreference}}\n研究问题：{{researchQuestions}}\n批次范围：{{batchRange}}\n总章节数：{{totalChapters}}\n本批大纲：{{outlineJson}}\n本批证据：{{ragEvidence}}\n\n请仅输出本批章节扩写结果 JSON：{chapters:[{index,title,summary,objective,sections:[{title,content,coreArgument,method,dataPlan,expectedResult,citations[]}]}],citationMap:[{citationIndex,chunkUid,paperId,title,url,year,source,excerpt,vectorScore,rerankScore,retrievedAt}]}\n要求：content/coreArgument 必须是完整中文论述；citations 只能使用 citationMap 中存在的整数索引。',
  NULL,
  0.2,
  0.9,
  'qwen-plus',
  'published',
  NULL,
  CURRENT_TIMESTAMP,
  CURRENT_TIMESTAMP
WHERE @template_id IS NOT NULL
  AND NOT EXISTS (
    SELECT 1 FROM prompt_versions WHERE template_id = @template_id AND version_no = 1
  );
