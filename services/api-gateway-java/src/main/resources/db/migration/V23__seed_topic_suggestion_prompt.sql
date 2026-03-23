-- V23: seed topic suggestion prompt template + version
INSERT INTO prompt_templates (
  template_key, name, scene, description, status, default_version_no,
  cache_enabled, cache_ttl_seconds, created_by
)
SELECT
  'topic_suggestion',
  'Topic Suggestion Prompt',
  'paper_outline',
  'Generate topic suggestions when user does not have a concrete paper title',
  'published',
  1,
  0,
  0,
  NULL
WHERE NOT EXISTS (
  SELECT 1 FROM prompt_templates WHERE template_key = 'topic_suggestion'
);

INSERT INTO prompt_versions (
  template_id, version_no, system_prompt, user_prompt, output_schema_json,
  model, temperature, top_p, status, published_by, published_at
)
SELECT
  t.id,
  1,
  'You are an academic topic advisor. Generate 3-5 feasible paper topic suggestions. Each item must include: title, researchQuestions (2-3), rationale (within 100 Chinese words), keywords (3-5). Return JSON array only.',
  'Direction: {{direction}}\nConstraints: {{constraints}}\nPrevious suggestions (avoid repetition): {{previousSuggestions}}',
  NULL,
  'qwen-plus',
  0.20,
  0.90,
  'published',
  NULL,
  CURRENT_TIMESTAMP
FROM prompt_templates t
WHERE t.template_key = 'topic_suggestion'
  AND NOT EXISTS (
    SELECT 1 FROM prompt_versions v WHERE v.template_id = t.id AND v.version_no = 1
  );
