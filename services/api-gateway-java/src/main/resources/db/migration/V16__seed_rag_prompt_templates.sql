-- Update paper_outline_generate prompt to include RAG evidence mapping instructions
INSERT INTO prompt_versions (template_id, version_no, system_prompt, user_prompt, created_at)
SELECT pt.id, 2,
  '你是学术论文大纲生成助手。根据主题、学科、文献和RAG证据生成论文大纲JSON。输出JSON必须包含：chapters（含sections和subsections）、references、researchQuestions。每个section需包含evidenceMapping数组，其中每个元素包含chunkUid、title和relevance（0-1之间的相关度评分）。',
  '主题：{{topic}}\n细化题目：{{topicRefined}}\n学科：{{discipline}}\n学位：{{degreeLevel}}\n方法偏好：{{methodPreference}}\n研究问题：{{researchQuestions}}\n文献：{{sources}}\nRAG证据：{{ragEvidence}}',
  NOW()
FROM prompt_templates pt
WHERE pt.template_key = 'paper_outline_generate'
LIMIT 1;

-- Update paper_outline_quality_check prompt to include evidence coverage scoring
INSERT INTO prompt_versions (template_id, version_no, system_prompt, user_prompt, created_at)
SELECT pt.id, 2,
  '你是论文质量审查助手。请对大纲进行质检并输出JSON：logicClosedLoop,methodConsistency,citationVerifiability,overallScore,issues。新增字段：evidenceCoverage（0-100整数，表示RAG证据对大纲各节的覆盖率）、uncoveredSections（未被证据覆盖的章节标题数组）。',
  '主题：{{topic}}\n细化题目：{{topicRefined}}\n引文样式：{{citationStyle}}\n大纲：{{outlineJson}}\nRAG证据：{{ragEvidence}}\n章节证据映射：{{chapterEvidenceMap}}',
  NOW()
FROM prompt_templates pt
WHERE pt.template_key = 'paper_outline_quality_check'
LIMIT 1;
