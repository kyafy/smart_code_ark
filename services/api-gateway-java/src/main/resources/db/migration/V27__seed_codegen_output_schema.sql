SET @project_template_id = (
  SELECT id
  FROM prompt_templates
  WHERE template_key = 'project_structure_plan'
  LIMIT 1
);

UPDATE prompt_versions
SET output_schema_json = JSON_OBJECT(
  '$schema', 'http://json-schema.org/draft-07/schema#',
  'type', 'array',
  'minItems', 5,
  'items', JSON_OBJECT(
    'type', 'string',
    'minLength', 1,
    'pattern', '^(?!\\/|.*\\.\\.)'
  )
)
WHERE template_id = @project_template_id
  AND version_no = (
    SELECT default_version_no
    FROM prompt_templates
    WHERE id = @project_template_id
  );

SET @file_template_id = (
  SELECT id
  FROM prompt_templates
  WHERE template_key = 'file_content_generate'
  LIMIT 1
);

UPDATE prompt_versions
SET system_prompt = CONCAT(
  IFNULL(system_prompt, ''),
  '\n\n项目文件结构（{{currentGroup}}组）：\n{{projectStructure}}\n\n当前生成文件：{{filePath}}\n\n约束：\n1. import/require 路径必须引用上述文件结构中实际存在的文件\n2. 依赖的类名/模块名必须与文件计划中的命名一致\n3. 不要引入文件计划中不存在的类或模块'
)
WHERE template_id = @file_template_id
  AND version_no = (
    SELECT default_version_no
    FROM prompt_templates
    WHERE id = @file_template_id
  )
  AND system_prompt NOT LIKE '%{{projectStructure}}%';
