# Python Agent 指标与故障遥测

Python API 与 IMAGE、VIDEO、GEO、ARTICLE、WORKFLOW Worker 继续复用
`app.observability.tracing` 的单一 OpenTelemetry 装配入口。Span 完成时从同一批安全属性派生
低基数指标；工具策略拒绝和 Worker processed/failed/inFlight 则在实际状态变化点记录。
Collector 或 Prometheus exporter 故障不参与业务 readiness，也不会触发 Provider 降级。

## 指标族

- `guanghe.operations` / `guanghe.operation.duration`：Chat Run、Agent、Model、Context、Tool、
  Worker、Rabbit、Java回报与 Provider 边界的次数和耗时。
- `guanghe.model.requests/retries/repairs/input_tokens/output_tokens`：真实模型请求、重试、
  结构修复及用量；格式解析失败后已产生的 Usage 仍由既有 Span 记录。
- `guanghe.context.loads`：Java/Local 授权上下文加载，包括预算、裁剪、认证和内存类失败。
- `guanghe.tool.calls` / `guanghe.tool.policy_denials`：只有 ToolExecutor 已开始的尝试计入 calls；
  输入 Schema 或策略在执行前拒绝单独计入 denials。
- `guanghe.provider.calls`：媒体 Provider submit/poll/recovery 的实际有界边界。
- `guanghe.worker.tasks` / `guanghe.worker.inflight`：所有 Worker 共用的处理结果和进程内在途量。
- `guanghe.workflow.events` / `guanghe.workflow.stage.duration`：WORKFLOW 的 consume、claim、
  context、execute、step/tool、activation、wait、resume、observation、replan 决策、lease 和 complete
  阶段次数与耗时。`workflow.stage`、`outcome`、`task.type`、错误类别和重试性均为固定集合；
  planId、stepId、executionNo 和 traceId 不作为标签。
- `guanghe.failures`：统一 `stableErrorCode` 分类后的失败操作，不含原始异常文本。

标签只允许进程角色、任务类型、意图/复杂度、操作、受控 Provider/Model、结果、稳定错误
类别、重试策略、submission state、Context来源及工具名称/版本/风险。任何 Run、Task、User、
Conversation、Trace、Provider ID、URL、正文、Prompt、参数、结果、JWT、Key 和异常文本都不得
成为标签或指标内容。非法或超长的受控值统一折叠为 `OTHER`。

计数器遵循 OTel/Prometheus 的进程重启语义：重启会产生 counter reset，查询应使用
`rate`/`increase`；inFlight 是 UpDownCounter，进程退出即归零。它们不能替代 Java 数据库、
RabbitMQ 管理指标或业务审计。告警规则位于 `deploy/prometheus-alert-rules.yaml`，须由外部
Prometheus 明确加载；仓库不安装 Prometheus/Grafana。Langfuse 仍为可选 Trace 后端，未配置
凭据时状态保持 `NOT_CONFIGURED`。

WORKFLOW 告警覆盖持续失败率、Java claim/control 不可用、lease/fencing 异常、等待与恢复差值、
replan 失败/耗尽以及 Provider UNKNOWN 积压。等待差值是运行告警信号，不是业务任务真相；最终
状态和补偿仍以 Java 控制面、RabbitMQ 与 Provider 恢复记录为准。
