# 光禾AI智能体框架 Python Agent 阶段任务与功能完成报告

> 报告日期：2026-08-28
>
> 仓库：`D:\coding-project\guanghe-agent`
>
> 分支：`master`
>
> 基线 Commit：`e40e9feefecb`
>
> 报告口径：当前工作区代码、自动化测试、M0 里程碑和 Provider Gateway 架构补丁

## 1. 结论摘要

当前状态分为三个层级：

| 层级 | 结论 | 说明 |
|---|---|---|
| Python 在 M0 的职责 | **已完成** | 已完成内部接口、HMAC 向量、Rabbit Schema、Provider Gateway 补丁的阅读、对齐和疑问登记 |
| 本地 Agent 与 Mock Worker 能力 | **已实现并通过自动化验证** | 已具备控制台、DeepSeek Adapter、领域子图、Chat Run、Provider Mock、独立 Worker 和评测体系 |
| M1 跨服务与生产链路 | **未完成** | 尚缺 Python HMAC、真实 Java Control Plane、Rabbit Consumer、真实任务 Provider 和生产部署 |

因此可以确认：

```text
PYTHON_M0_CONTRACT_REVIEW=COMPLETE
PYTHON_M0_CONTRACTS_ALIGNED=true
PROVIDER_GATEWAY_PATCH_AVAILABLE=true
PYTHON_M1_READY=false
```

`PYTHON_M1_READY=false` 不否定 Python M0 已完成，只说明当前不能把本地后续能力描述成已经完成生产联调。

## 2. Python M0 已完成任务

M0 文档明确规定：Python 在本阶段不要求提交 Agent、模型、Worker 或业务功能，只完成通信契约和架构边界准备。

| M0 任务 | 完成情况 | 证据 |
|---|---|---|
| 阅读 Java 调 Python 的内部接口 | 已完成 | 已记录 readiness、capabilities、loopback 地址、异常降级和 `traceparent` 约定 |
| 阅读 Python 调 Java 的内部接口 | 已完成 | 已记录反向探针、HMAC Header、五行 canonical string 和验签顺序 |
| 核对 HMAC 固定向量 | 已完成 | `contracts/hmac/hmac-v1-test-vector.json` 已与 Java 共同向量语义对齐 |
| 理解防重放要求 | 已完成 | 已确认 timestamp 默认正负 60 秒、Nonce UUID、Redis `SET NX + TTL`、TTL 至少 120 秒和 fail closed |
| 同步 Rabbit v1 Schema | 已完成 | `contracts/rabbit/task-execution-requested-v1.schema.json` 已与 Java 发起的 Schema 对齐 |
| 阅读 Provider Gateway 补丁 | 已完成 | 已确认 Gateway 是 Python 进程内治理模块，以及 Agent、Gateway、Provider、Java 的职责边界 |
| 避免假契约和字段分叉 | 已完成 | 契约测试独立重算 SHA-256/HMAC，并已登记待 Java 确认事项 |
| 列出联调疑问 | 已完成 | 已登记 Java Internal OpenAPI 来源、测试环境 Gate 证据和测试向量 Secret 特例 |

详细记录见 `docs/m0-python-preparation.md`。

以下事项属于 Java、Web 或运维的 M0 Gate，不是 Python M0 任务：Java systemd、账号登录、Redis 限流、Rabbit TLS、Java 重启恢复、Nginx HTTPS、Web 登录和公网端口边界。

## 3. 当前工作区额外完成的 Agent 功能

以下功能已经超出 Python 的 M0 必需范围，当前按“本地实现并验证”统计，不作为 M0 交付范围扩大依据。

### 3.1 Agent API 与开发控制台

- FastAPI 应用工厂，导入模块时不主动建立外部连接。
- 进程存活、Bundle/模型网关就绪和能力握手接口。
- Chat Run 创建、状态查询、SSE 事件订阅和 Stop。
- 支持 `Last-Event-ID` 续读和稳定事件序号。
- 本地 Agent 控制台可输入消息、经营空间 ID 和商品 ID。
- 控制台显示 `MOCK/REAL`、Provider、模型、Bundle、Token 用量、SSE 增量及停止状态。
- 生产环境自动隐藏开发控制台。

Chat Run 只管理本进程内的临时执行状态，不代表 Java 的正式业务状态。

### 3.2 模型网关与 DeepSeek Adapter

- 支持 `AUTO`、`LOCAL`、`DEEPSEEK` 三种选择模式。
- 本地模式使用 deterministic gateway，便于无密钥测试。
- 生产环境禁止自动回退到 Local Mock。
- DeepSeek Adapter 已实现：
  - `/models` 探活；
  - `/chat/completions` 调用；
  - 非思考模式；
  - 结构化意图输出；
  - 结构化领域参数输出；
  - 普通回答流式增量；
  - Token 与延迟统计；
  - 超时、认证、限流、5xx 和非法响应的安全错误分类；
  - 有界重试；
  - JSON Fence 兼容和 Pydantic 校验。
- 领域参数在 Adapter 和 Agent 图边界执行两次白名单过滤。
- Provider 凭据使用 `SecretStr`，错误响应不回显密钥或原始 Provider 正文。

本报告执行的是 Adapter + MockTransport 自动化测试，没有重新调用真实 DeepSeek，因此不能将本次报告视为新的 live 模型验收记录。

### 3.3 主路由图与四个领域子图

当前架构是“一个有界主 Agent + 多领域子图”，不是多个能够独立决策和互相协作的多 Agent 系统。

- 主图负责加载授权上下文、规范化消息、识别意图、选择子图和最终边界校验。
- 已实现四个独立 LangGraph 子图：
  - `commerce-geo-v1`；
  - `commerce-image-v1`；
  - `commerce-video-v1`；
  - `commerce-article-v1`。
- 每个领域都有独立、严格的 Pydantic 参数 Schema。
- GEO 要求 `selectedSpaceId`；IMAGE、VIDEO、ARTICLE 要求 `selectedProductId`。
- 缺少上下文或描述时返回 `interaction.required`。
- 参数满足最低要求时只返回 `task-draft.proposed`，等待 Java 再次校验。
- “确认、开始、执行”等含糊指令不会直接触发既有业务动作。

### 3.4 Runtime Harness 与业务边界

- 校验 Run deadline。
- 限制输入 Token、图步骤、模型调用和工具调用预算。
- 禁止 Python 输出 Java 所有的确认令牌、报价、积分扣减和支付结果字段。
- 拒绝将 `MEDIA` 作为 Python Agent 任务能力。
- 首版工具注册表仅开放：
  - `interaction.propose`；
  - `task_draft.propose`。
- 两个工具均为 `PURE_FUNCTION/PREPARE`，不允许注册 `COMMIT` 工具。

Python 当前不会进行鉴权裁决、价格计算、积分扣减、正式任务创建、发布或 PostgreSQL 写入。

### 3.5 Prompt Bundle 与能力握手

- 版本化 YAML Prompt/Agent Profile Bundle。
- 严格 Schema、重复编码和交叉引用校验。
- Bundle 与 Prompt 分别生成稳定 SHA-256 Hash。
- Bundle 缺失或非法时进程仍存活，但 readiness 返回 `NOT_READY`。
- capabilities 返回服务版本、Bundle 版本、Hash、支持领域和 Provider 明确状态。
- Provider 状态区分 `AVAILABLE`、`CONFIGURED`、`UNAVAILABLE`、`NOT_CONFIGURED`、`MODEL_NOT_CONFIGURED` 和 `DISABLED`，不会仅凭配置存在就伪报可用。

### 3.6 Provider-neutral 契约与全领域 Mock

- 已定义统一的任务请求、结果、Artifact、Usage、错误分类和提交确定性契约。
- Provider Mock 支持 GEO、IMAGE、VIDEO、ARTICLE。
- Mock 结果均明确携带 `mock: true`，GEO 结果明确声明不代表真实平台回答。
- 支持以下故障注入：
  - `AUTH`；
  - `RATE_LIMIT`；
  - `UPSTREAM_5XX`；
  - `TIMEOUT`；
  - `INVALID_RESPONSE`；
  - `SAFETY_REJECTED`；
  - `UNKNOWN_SUBMIT`。
- `MEDIA` 在 Provider 契约层被拒绝。

### 3.7 独立 Worker 骨架与控制台

- Worker 拥有独立 ASGI 应用、CLI 入口、生命周期和默认端口 `8010`。
- 提供 health、readiness、运行状态、开发控制台、本地任务投递和状态查询。
- 本地模式使用进程内 Broker、Java Control Plane Stub 和 Provider Mock。
- 已实现 Claim-before-ACK。
- 重复投递不会再次执行 Provider。
- 非法消息进入 reject/DLQ 语义路径。
- Java Control Plane 不可用时执行 nack/requeue 语义。
- 只对“确认尚未提交”的可重试错误进行受限重试。
- Provider 提交状态未知时禁止盲目重试。
- 支持并发上限、停止接单和宽限期内排空在途任务。
- 生产环境检测到 Local Stub 或 Mock Provider 时返回 `NOT_READY`，不会伪装成真实 Worker。

Worker 当前是可验证的本地运行骨架，不是真实 Rabbit/Java/Provider 生产链路。

### 3.8 契约、配置和可观测性

- Python Internal OpenAPI：`contracts/agent-openapi.yaml`。
- Chat Run 事件 Schema：`contracts/events/chat-run-event-v1.schema.json`。
- Rabbit 任务消息 Schema：`contracts/rabbit/task-execution-requested-v1.schema.json`。
- HMAC 固定测试向量：`contracts/hmac/hmac-v1-test-vector.json`。
- 配置只从 `GUANGHE_AGENT_` 环境变量读取。
- API 和 Worker 默认只绑定 loopback。
- 已提供 `.env.example`，真实密钥由 `.env.local` 承载并被 Git 忽略。
- 已实现最小 JSON 日志白名单和异常类型记录。

当前可观测性属于基础日志和 Agent 事件，不等同于完整 OpenTelemetry 或 LangSmith 分布式追踪。

## 4. 当前可用接口与后台地址

### Agent API，默认端口 8000

| 地址 | 作用 |
|---|---|
| `http://127.0.0.1:8000/health` | API 进程存活检查 |
| `http://127.0.0.1:8000/internal/v1/health/ready` | Bundle 与模型网关就绪检查 |
| `http://127.0.0.1:8000/internal/v1/capabilities` | Agent、Bundle、领域和 Provider 能力握手 |
| `http://127.0.0.1:8000/internal/v1/chat-runs` | 创建有界 Chat Run |
| `http://127.0.0.1:8000/dev/agent` | Agent 开发控制台 |
| `http://127.0.0.1:8000/docs` | API 文档 |

### Worker，默认端口 8010

| 地址 | 作用 |
|---|---|
| `http://127.0.0.1:8010/health` | Worker 进程存活检查 |
| `http://127.0.0.1:8010/internal/v1/health/ready` | Worker 就绪检查 |
| `http://127.0.0.1:8010/internal/v1/worker/status` | Worker 运行状态 |
| `http://127.0.0.1:8010/dev/worker` | Worker Mock 开发控制台 |
| `http://127.0.0.1:8010/docs` | Worker API 文档 |

以上都是本机开发地址，不是测试服务器公网地址。

## 5. 自动化验证结果

报告生成时执行了以下检查：

| 检查 | 结果 |
|---|---|
| `uv run pytest` | **53/53 通过** |
| `uv run ruff check .` | 通过 |
| `uv run ruff format --check .` | 通过，70 个文件已格式化 |
| `uv run pyright` | 通过，0 errors、0 warnings |
| `uv lock --check` | 通过 |
| `git diff --check` | 通过，仅有 Windows LF/CRLF 提示 |
| 本地 Agent regression | **36/36 通过** |

测试覆盖：

- OpenAPI、SSE、Rabbit Schema 和 HMAC 固定向量契约；
- Bundle Hash、非法引用、Bundle 缺失和 `MEDIA` 边界；
- Chat Run、SSE、Stop、开发控制台和安全 404；
- 主路由及四个领域子图；
- 缺参澄清、参数白名单、任务草稿和含糊确认边界；
- DeepSeek 探活、结构化输出、流式响应、Token、401 安全错误；
- Provider Mock 四领域成功路径和七类故障；
- Worker 独立运行、Claim-before-ACK、幂等、重试和优雅排空；
- 36 条 GENERAL、GEO、IMAGE、VIDEO、ARTICLE、CONFIRMATION 和安全边界合成评测。

当前未配置代码覆盖率工具和最低阈值，因此不能声称具体代码覆盖率百分比。

## 6. 尚未完成且不能误报的功能

以下内容不属于 Python M0，但进入 M1 或生产前必须完成：

1. Python HMAC Server Middleware。
2. `/internal/v1/capabilities` 等内部接口的真实 HMAC 保护。
3. Python 调 Java 的 HMAC Client/Stub。
4. Redis TLS Nonce `SET NX + TTL` 防重放实现。
5. `traceparent` 跨 Java、Python、Rabbit、Provider 的完整延续。
6. 真实 Java 授权 Context API；当前仍使用本地 Context Provider。
7. 真实 RabbitMQ Consumer、ACK/NACK/DLQ 和连接恢复。
8. 真实 Java Worker Control Plane Client，包括 Claim、Context、Heartbeat 和结果回报。
9. 真实 GEO、图片、视频和文章任务 Provider Adapter。
10. Python 测试服务器 systemd、日志轮转和部署验收。
11. Run/Worker 状态持久化；当前状态仅存在于本进程内存。
12. 完整 OpenTelemetry/LangSmith 级分布式观测。

Worker DTO 草稿还需要与冻结的 Rabbit Schema 完全对齐：

- `traceparent` 当前代码允许为空，而 Schema 要求有效 W3C 格式；
- 当前代码允许普通字符串 ID，而 Schema 要求十进制字符串；
- 公共枚举仍包含 `MEDIA`，Worker 目前在收到后拒绝，后续应在消息模型边界直接限制。

## 7. Git 与交付状态

当前已提交基线为：

```text
e40e9fe feat(agent): 完成阶段1运行骨架并接入DeepSeek
```

本报告所盘点的多领域子图、Provider Mock、Worker、评测和部分契约仍位于未提交工作区。因此准确表述是“工作区已实现并验证”，暂不能表述为“已推送、已部署或已发布”。

提交前应再次执行：

```powershell
uv run ruff check .
uv run ruff format --check .
uv run pyright
uv run pytest
uv run python -m evals.run_agent_evals --mode local --suite regression
git diff --check
```

## 8. 阶段判断

```text
Python Agent M0 职责：完成
本地 Agent 控制台：可用
DeepSeek Adapter 代码链路：完成并通过模拟上游测试
四领域子图：完成并通过测试
Provider Mock：完成并通过测试
独立 Worker 本地骨架：完成并通过测试
真实 Java/Rabbit/Provider 联调：未完成
Python M1 Ready：否
生产 Ready：否
```

当前可以继续进行 Python 内部开发和本地验收；正式进入跨服务 M1 时，应以 Java 冻结的内部 OpenAPI、HMAC 和 Worker Control Plane 契约为依据，不能由 Python 自行猜测业务字段。
