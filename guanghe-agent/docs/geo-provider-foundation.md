# GEO Provider Foundation

## 边界

本模块负责获取并规范化真实平台样本，以及基于这些不可变样本生成分析。正式 GEO Worker
复用通用 Rabbit Consumer 和 Java 控制面；Java 仍负责正式任务、积分、确认、调度、持久化和
业务审计。Python 没有数据库连接，也不以本地文件、Redis 或进程内存作为恢复真相。

Canonical `GeoObservation` 保留平台、问题引用、样本序号、成功/失败、原始回答、引用、
Provider/模型、时间、稳定错误码、用量和真实 Provider HTTP 调用次数。失败样本的回答必须为空，
且不会被其他平台回答或模板补齐。引用只接受无凭据的 HTTP(S) URL。

## 当前适配实现（2026-09-24 按代码更新）

下表描述仓库实现，不是供应商线上健康证明；覆盖原 2026-09-08 文档中过时的
DeepSeek 不支持采样、Qwen 只走 Workspace Responses、Kimi 内置搜索三处描述。

| 平台 | 官方接口形态 | 联网方式 | 引用来源 | 适配决策 |
| --- | --- | --- | --- | --- |
| DOUBAO | 北京方舟 Responses API | `web_search` 内置工具 | Responses 搜索来源 | 独立 Responses Adapter |
| DEEPSEEK | 博查 Web Search → DeepSeek Chat | 第三方博查辅助检索，不冒充原生搜索 | 博查有效来源 | 搜索/模型凭据均必需，无来源失败 |
| QWEN | 千问 AI 平台或旧北京通用 / Workspace Chat Completions | `enable_search` + `forced_search` | 兼容 Chat 协议不保证返回来源，不构造引用 | 保留当前模型与 turbo 策略 |
| WENXIN | 千帆 AI Search Chat Completions | `baidu_search_v2` | 顶层 `references` | 独立鉴权头及请求参数 |
| KIMI | Moonshot Search Pro → Chat Completions | 同账号区域的独立检索再回答 | Search Pro 来源 | 两步及其重试均计数，鉴权失败不跨区回退 |
| YUANBAO | 腾讯 TokenHub Chat Completions | `web_search_options` | `message.search_results` | 以 `hy3` 等实际配置模型为准 |
| ZHIPU | 智谱 Chat Completions | `web_search` 工具 | 顶层 `web_search` | 独立工具参数及响应提取 |

模型名不写死为旧系统常量，真实 Gate 只使用受控环境显式配置。Endpoint 仅允许各平台官方
HTTPS Host；Qwen 允许 `maas.qianwenaiapi.com`、旧北京通用和 Workspace Host。API 返回的错误正文不进入异常、日志或业务结果。

千问新入口的受控配置为 `GUANGHE_AGENT_GEO_QWEN__ENDPOINT=https://maas.qianwenaiapi.com/compatible-mode/v1/chat/completions`，
配套密钥仅保存在 GEO Worker 的受限环境文件中，不与图片、视频或普通对话配置混用。
本次只新增官方 Host 兼容，不切换 API 协议、不修改模型、搜索参数、重试或计费。
协议依据：[千问 AI 平台联网搜索](https://platform.qianwenai.com/docs/developer-guides/tool-calling/web-search)。
该兼容协议支持强制联网，但不返回结构化搜索来源；验收不能把正文里的链接或 HTTP 200 当成可核查的搜索证据。

## 旧系统迁移审查

保留：七个平台代码、官方 Host、鉴权形态、搜索开关、引用位置及 Kimi 两段式调用知识。

舍弃：旧数据库模型、用户权限、原回答人工改写、模板答案、伪 KPI、Celery、全局 Session、
Redis 任务真相、明文 Secret、原始错误正文透传，以及任何跨平台自动 Fallback。

## Java 控制面与恢复

Java Internal OpenAPI 已冻结：

- 队列 `zhixiaohe.agent.geo` 与路由键 `agent.geo`；
- 通用 Claim、Execution Context、Heartbeat 和 Execution Event；
- `POST/GET /internal/v1/tasks/{taskId}/geo-observations`；
- `POST /internal/v1/tasks/{taskId}/geo-completion`；
- Java 授权的问题、平台、采样轮次、deadline、Provider 请求预算和并发上限。

Worker 在 Claim 成功后 ACK。每个确定性 unit（`questionId:platformCode:sampleNo`）完成后立即幂等
上报；Java 返回 `CREATED` 或 `DUPLICATE` 才视为可靠保存。新 execution 只从 Java observations
恢复，成功和失败 unit 均跳过，不重复调用 Provider。至少一个样本成功时才使用 Java 已保存样本
做一次 DeepSeek 结构化综合；Java 接受 Completion 后决定 `SUCCEEDED`/`PARTIAL` 和积分结算。

## P0 超时、并发与预算边界

豆包 connect/pool/write/read 默认 5/5/10/120 秒，额外的 150 秒单次绝对期限和 Java
任务剩余期限共同限制出站。显式配置覆盖默认值，不提高既有最大请求次数。只有暂态错误重试，
退避 15–30 秒并尊重 Retry-After；过长等待直接结束，不缩短服务端要求的等待后重发。

最多 210 个已授权单元按轮次等待额度，平台额度在任务额度之前获取，退避释放额度。
慢豆包不会占用所有任务槽阻塞其他平台。豆包并发上限只覆盖同一执行器/Worker 进程，
不能描述为跨进程、跨系统的账号全局配额。任务期限还覆盖排队和综合；综合无余量时返回
明确的基础统计，租约失效直接停止。Java 恢复使用首次 Run 开始时间而非新租约时间；
配置时长尚未冻结入库，完整 deadline/预算持久化属于 P1。

阶段超时与绝对期限是两层不同约束：[HTTPX timeout](https://www.python-httpx.org/advanced/timeouts/)
定义的是各阶段等待；[asyncio timeout](https://docs.python.org/3/library/asyncio-task.html#timeouts)
限制整个异步调用。本地真实短连接测试分别覆盖延迟成功、读取超时、持续分段返回触发总时限，
不把 MockTransport 当作网络计时验证。所有本轮测试均使用合成数据，无供应商付费调用。

新增诊断沿用 JSON 日志白名单，记录平台、attempt、HTTP 状态、超时阶段、单次耗时、
退避时间、额度等待时间和调用次数。超时或网络错误标记上游结果未知；不输出请求正文、
供应商错误正文或令牌。当前仍没有跨崩溃的出站前预留账本，不能承诺外部调用严格一次。
综合失败后 SYSTEM/0 只表示降级统计不用模型，不是已尝试综合的费用记录；未知费用不能当零。
