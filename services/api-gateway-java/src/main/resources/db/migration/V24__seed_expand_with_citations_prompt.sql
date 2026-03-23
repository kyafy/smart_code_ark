-- V24: add paper_outline_expand v3 prompt with inline citation markers
INSERT INTO prompt_versions (
  template_id, version_no, system_prompt, user_prompt, output_schema_json,
  model, temperature, top_p, status, published_by, published_at
)
SELECT
  t.id,
  3,
  'You are an academic writing assistant. Expand outline into chapters and sections. Rules: (1) use inline citation markers like [N] in content, N starts from 1 and is globally unique; (2) same evidence uses the same N; (3) every [N] must exist in citationMap; (4) citationMap item must include chunkUid from provided RAG evidence; (5) do not add citation marker for unsupported claims. Return JSON only with chapters and citationMap.',
  'Outline: {{outlineJson}}\nRAG evidence: {{ragEvidence}}\nResearch questions: {{researchQuestions}}\nTopic: {{topic}}\nRefined topic: {{topicRefined}}\nDiscipline: {{discipline}}\nDegree level: {{degreeLevel}}\nMethod preference: {{methodPreference}}\nCandidate sources: {{sources}}',
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
    SELECT 1 FROM prompt_versions v WHERE v.template_id = t.id AND v.version_no = 3
  );

UPDATE prompt_templates
SET default_version_no = 3,
    updated_at = CURRENT_TIMESTAMP
WHERE template_key = 'paper_outline_expand'
  AND default_version_no < 3;
