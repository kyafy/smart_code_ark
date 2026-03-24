-- Update paper_outline_expand prompt v4 → v5: add section count consistency constraint
SET @template_id = (SELECT id FROM prompt_templates WHERE template_key = 'paper_outline_expand' LIMIT 1);

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
  published_at
)
SELECT
  @template_id,
  5,
  '你是严谨的论文写作助手。仅输出合法 JSON，不要输出 Markdown 或解释。禁止使用任何占位文本（placeholder/TBD/待补充/暂无正文）。每个 section 必须有完整中文 content 与 coreArgument；若证据不足，给出保守但可读的学术表述并说明不确定性。每章的 sections 数量必须与输入大纲中该章的 sections 数量严格一致，逐一扩写每个 section，不得合并或省略。',
  '主题：{{topic}}\n细化题目：{{topicRefined}}\n学科：{{discipline}}\n学位层次：{{degreeLevel}}\n方法偏好：{{methodPreference}}\n研究问题：{{researchQuestions}}\n论文大纲：{{outlineJson}}\n候选文献：{{sources}}\nRAG证据：{{ragEvidence}}\n\n请输出 JSON：\n{\n  "topic": "string",\n  "topicRefined": "string",\n  "researchQuestions": ["string"],\n  "chapters": [\n    {\n      "index": 1,\n      "title": "string",\n      "summary": "string",\n      "objective": "string",\n      "sections": [\n        {\n          "title": "string",\n          "content": "必须是完整中文段落，禁止占位文本",\n          "coreArgument": "必须是完整中文论点",\n          "method": "string",\n          "dataPlan": "string",\n          "expectedResult": "string",\n          "citations": [1,2]\n        }\n      ]\n    }\n  ],\n  "citationMap": [\n    {\n      "citationIndex": 1,\n      "chunkUid": "string",\n      "paperId": "string",\n      "title": "string",\n      "url": "string",\n      "year": 2024,\n      "source": "string",\n      "excerpt": "string",\n      "vectorScore": 0.0,\n      "rerankScore": 0.0,\n      "retrievedAt": "2026-01-01T00:00:00"\n    }\n  ]\n}\n\n约束：\n1）content/coreArgument 不得为空、不得出现 placeholder/TBD/待补充/暂无正文。\n2）chapters 与 sections 数量必须与输入大纲严格一致，逐一扩写每个 section，不得合并或省略。\n3）citations 只能使用 citationMap 中存在的整数索引；没有证据时返回空数组，不得编造索引。',
  NULL,
  0.2,
  0.9,
  'qwen-plus',
  'published',
  NULL,
  CURRENT_TIMESTAMP
WHERE @template_id IS NOT NULL
  AND NOT EXISTS (
    SELECT 1
    FROM prompt_versions pv
    WHERE pv.template_id = @template_id
      AND pv.version_no = 5
  );

UPDATE prompt_templates
SET default_version_no = 5,
    updated_at = CURRENT_TIMESTAMP
WHERE id = @template_id
  AND @template_id IS NOT NULL;

-- Update paper_outline_expand_batch prompt v1 → v2: add section count consistency constraint
SET @batch_template_id = (SELECT id FROM prompt_templates WHERE template_key = 'paper_outline_expand_batch' LIMIT 1);

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
  @batch_template_id,
  2,
  '你是严谨的论文写作助手。你将只扩写论文的一部分章节。必须仅输出合法 JSON，不得输出 Markdown。禁止占位文本（placeholder/TBD/待补充/暂无正文）。每章的 sections 数量必须与输入大纲中该章的 sections 数量严格一致，逐一扩写每个 section，不得合并或省略。',
  '全局主题：{{topic}}\n细化题目：{{topicRefined}}\n学科：{{discipline}}\n学位层次：{{degreeLevel}}\n方法偏好：{{methodPreference}}\n研究问题：{{researchQuestions}}\n批次范围：{{batchRange}}\n总章节数：{{totalChapters}}\n本批大纲：{{outlineJson}}\n本批证据：{{ragEvidence}}\n\n请仅输出本批章节扩写结果 JSON：{chapters:[{index,title,summary,objective,sections:[{title,content,coreArgument,method,dataPlan,expectedResult,citations[]}]}],citationMap:[{citationIndex,chunkUid,paperId,title,url,year,source,excerpt,vectorScore,rerankScore,retrievedAt}]}\n\n重要约束：\n1）content/coreArgument 必须是完整中文论述。\n2）每章的 sections 数量必须与输入大纲中对应章节的 sections 数量完全一致，逐一扩写，不得合并或省略。\n3）citations 只能使用 citationMap 中存在的整数索引。',
  NULL,
  0.2,
  0.9,
  'qwen-plus',
  'published',
  NULL,
  CURRENT_TIMESTAMP,
  CURRENT_TIMESTAMP
WHERE @batch_template_id IS NOT NULL
  AND NOT EXISTS (
    SELECT 1 FROM prompt_versions WHERE template_id = @batch_template_id AND version_no = 2
  );

UPDATE prompt_templates
SET default_version_no = 2,
    updated_at = CURRENT_TIMESTAMP
WHERE id = @batch_template_id
  AND @batch_template_id IS NOT NULL;
