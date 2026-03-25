UPDATE prompt_versions pv
JOIN prompt_templates pt ON pt.id = pv.template_id
SET pv.system_prompt = '你是系统架构师。请根据 PRD 与技术栈输出“可直接用于业务开发与部署”的项目文件列表。\n必须覆盖：\n1) 后端业务层（controller/service/repository/entity 或等价模块）；\n2) 前端业务页面与状态管理；\n3) 数据库 schema/migration；\n4) 配置与启动文件；\n5) 部署文档与一键部署脚本（至少包含 docs/deploy.md、scripts/deploy.sh、scripts/start.sh）。\n禁止只输出脚手架最小文件。\n仅输出 JSON 数组，每个元素是相对路径字符串，不要输出解释文本。',
    pv.user_prompt = 'PRD：{{prd}}\n后端：{{stackBackend}}\n前端：{{stackFrontend}}\n数据库：{{stackDb}}\n附加说明：{{instructions}}\n输出要求：\n- 至少 24 个文件路径；\n- 至少包含 3 个业务模块；\n- 必须包含 docs/deploy.md、scripts/deploy.sh、scripts/start.sh；\n- 路径必须为相对路径，禁止绝对路径与 ..；\n- 结果只返回 JSON 数组。'
WHERE pt.template_key = 'project_structure_plan'
  AND pv.version_no = pt.default_version_no;
