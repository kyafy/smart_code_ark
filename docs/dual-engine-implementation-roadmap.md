# 双引擎改造实施计划与评审清单

## 1. 改造范围

- 代码生成主链路（generate/modify）
- DeepAgent 节点执行模型
- Java 内部工具调用策略
- 节点级观测与追踪

## 2. 分阶段计划

### Phase 0：评审准备

- 输出边界文档与状态协同协议
- 明确可降级接口与不可降级接口
- 定义节点级指标字段

交付物：

- `docs/dual-engine-boundary-and-protocol.md`
- `docs/node-observability-spec.md`

### Phase 1：requirement_analyze 试点

- 内置优先 + Java 增强 + 超时降级
- 增加节点级 metrics 回写
- 验证“超时不阻断主链路”

验收：

- requirement_analyze timeout 率显著下降
- 节点失败原因更可解释

### Phase 2：并行生成域扩展

- 推广到 `codegen_backend / codegen_frontend / sql_generate`
- 引入并行子任务模型与汇总节点
- 加强契约校验节点

验收：

- 总链路时延下降
- 子任务失败可局部重试

### Phase 3：构建与运行稳定性

- build_fix 回路标准化
- runtime_smoke_test 报告结构化
- 失败分级与自动回退策略完善

验收：

- 构建通过率提升
- 回归失败可复现性提升

### Phase 4：灰度与收敛

- 10% → 50% → 100% 灰度
- 监控 SLO 达标后全量
- 完成文档与运行手册闭环

## 3. 评审清单（设计）

- 是否明确了双引擎边界与仲裁规则
- 是否定义了节点准出双门槛
- 是否定义了超时预算与迭代上限
- 是否区分了可降级与不可降级接口
- 是否具备 run_id 级追踪能力

## 4. 评审清单（实现）

- 节点状态流转是否合法
- 回写接口是否幂等
- 失败重试是否可控（无风暴）
- 并行子任务是否可汇总可恢复
- 日志是否覆盖关键字段

## 5. 评审清单（上线）

- 关键告警已配置
- 回滚开关可用
- 灰度比例可动态调整
- 历史任务与新任务兼容
- 运维手册与排障手册完成

## 6. 发布开关建议

- `DEEPAGENT_REQUIREMENT_BUILTIN_FIRST`
- `DEEPAGENT_REQUIREMENT_ENABLE_JAVA_ENRICH`
- `DEEPAGENT_REQUIREMENT_JAVA_ENRICH_TIMEOUT`
- `DEEPAGENT_NODE_MAX_ROUNDS`
- `DEEPAGENT_NODE_TIMEOUT`

## 7. 风险与缓解

- 风险：模型迭代轮次失控  
  缓解：强制 `max_rounds` + `node_timeout`

- 风险：降级路径长期触发  
  缓解：监控降级率并追踪慢工具热点

- 风险：双链路结果不一致  
  缓解：统一规则校验与最终提交仲裁

## 8. 成功标准

- 任务完成率提升
- 超时失败率下降
- 平均恢复时间下降
- 节点级可观测完整度达标
