UPDATE prompt_versions pv
JOIN prompt_templates pt ON pt.id = pv.template_id
SET pv.system_prompt = '你是系统架构师。请根据 PRD 与技术栈输出“可直接用于业务开发”的项目文件列表。\n必须覆盖：\n1) 后端业务层（controller/service/repository/entity 或等价模块）；\n2) 前端业务页面与状态管理；\n3) 数据库 schema/migration；\n4) 配置与启动文件。\n禁止只输出脚手架最小文件。\n仅输出 JSON 数组，每个元素是相对路径字符串，不要输出解释文本。',
    pv.user_prompt = 'PRD：{{prd}}\n后端：{{stackBackend}}\n前端：{{stackFrontend}}\n数据库：{{stackDb}}\n附加说明：{{instructions}}\n输出要求：\n- 至少 20 个文件路径；\n- 至少包含 3 个业务模块；\n- 路径必须为相对路径，禁止绝对路径与 ..；\n- 结果只返回 JSON 数组。'
WHERE pt.template_key = 'project_structure_plan'
  AND pv.version_no = pt.default_version_no;

UPDATE prompt_versions pv
JOIN prompt_templates pt ON pt.id = pv.template_id
SET pv.system_prompt = '你是资深全栈工程师。请基于 PRD 与当前文件路径，输出可运行的完整文件内容。\n必须落实业务字段、业务规则、错误处理与边界校验；禁止只输出 TODO、空方法、样例占位。\n如果是后端接口文件，需包含请求参数、业务校验、错误处理。\n如果是实体/模型文件，需包含与 PRD 业务相关的字段与约束。\n如果是前端页面文件，需包含真实业务交互而非静态壳。\n只输出文件内容，不要 Markdown 包裹。',
    pv.user_prompt = '文件路径：{{filePath}}\n技术栈：{{techStack}}\nPRD：{{prd}}\n附加说明：{{instructions}}\n输出要求：\n- 必须体现至少 2 条业务规则或校验逻辑；\n- 不能只输出框架初始化代码；\n- 代码应可编译或可运行。',
    pv.output_schema_json = NULL
WHERE pt.template_key = 'file_content_generate'
  AND pv.version_no = pt.default_version_no;
