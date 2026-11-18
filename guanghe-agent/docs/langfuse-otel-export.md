# Langfuse OpenTelemetry 导出

## 结论与拓扑

应用继续只使用 `app.observability.tracing` 的 OpenTelemetry SDK，不引入 Langfuse SDK、
LangSmith 或第二套埋点。API 与各 Worker 通过 OTLP/HTTP 向 loopback Collector 发 Span：

```text
API / IMAGE / VIDEO / GEO / ARTICLE
                 |
                 | OTLP/HTTP (no credential)
                 v
        loopback OTel Collector
          |                 |
          v                 v
  bounded local file   optional Langfuse OTLP/HTTP
```

远端导出完全属于 Collector 配置。Langfuse Endpoint 和 Basic Authorization 不进入应用环境、
应用 readiness、日志或 Git。Collector 远端发送使用 5 秒超时、60 秒最大重试窗口和 512 条有界
队列；远端不可用或队列溢出不会改变 API、Worker 或任务状态。

## 状态语义

`readiness` 与 `capabilities` 公开相同的安全状态：

- `DISABLED`：应用未启用 Langfuse 导出意图。
- `NOT_CONFIGURED`：已启用，但部署者没有声明 Collector 远端配置完整。
- `READY`：应用 OTel 与 Collector 远端接线均已声明完成；这不是远端摄取证明。
- `DEGRADED`：远端接线已声明，但应用 OTel pipeline 未就绪。

Langfuse 是非业务依赖，以上任何状态都不改变整体业务 readiness。远端是否真的摄取必须通过
独立、可检索的 Gate 验证；无凭据环境应如实保持 `NOT_CONFIGURED`。

## 安全字段映射

所有 ID 先转成不可逆 `sha256:` 短引用。conversation ref 映射为
`langfuse.session.id`；run/task ref、Bundle/Profile 版本与 Hash 映射为 Trace metadata。
不会发送用户 ID、原始 conversation/run/task ID、用户正文、Prompt、Java Context、工具参数、
工具完整输出、模型/Provider 原始响应、JWT、Context Ref、Authorization、密钥或媒体签名 URL。

Span 类型与允许字段如下：

- `generation`：Provider、模型、操作、输入/输出 Token、请求/修复次数、稳定错误码和延迟。
- `agent`：plan version、step count、replan count、步骤结果和稳定错误码。
- `tool`：工具名/版本、风险、尝试、结果、稳定错误码和延迟；不含 args/output。

Collector 在两个 pipeline 前再次删除消息正文、Prompt/completion、Langfuse input/output、数据库
语句、Authorization/Set-Cookie、用户 ID、完整 URL 和 Query，作为纵深防护。

## 失败分类

导出的失败只含稳定码、类别和重试策略：模型配置/鉴权/余额、模型限流、模型传输、模型结构化
输出、授权 Context、工具 Policy、工具执行、预算、deadline、Provider UNKNOWN/恢复，以及兜底
内部错误。异常正文、栈和 Provider 响应不会写入 Span。UNKNOWN 标记为 `RECOVERY_ONLY`，避免
可观测平台诱导重复提交媒体任务。

## 采样边界

test 环境由代码固定为 100% 采样。production 默认使用 10% ParentBased head sampling，部署者可
显式调整。head sampling 在 Trace 开始时决策，所以当前不能保证保留全部错误 Trace。若未来要求
“错误全留”，必须在 Collector 引入 tail sampling、容量隔离和延迟预算并完成独立 Gate；当前文档
不宣称已实现该能力。

## 部署选择

无 Langfuse 凭据时安装 `otel-collector-test.yaml`。具备受控凭据并准备执行远端 Gate 时，才把
`otel-collector-langfuse-test.yaml` 作为 `/etc/zhixiaohe/otel-collector-test.yaml` 安装，并创建
`/etc/zhixiaohe/langfuse-otel.env`（`root:root / 0600`）。先用部署版本的 `otelcol-contrib`
校验配置，再重启 Collector。应用只配置两个非秘密布尔值，不接收远端凭据。
