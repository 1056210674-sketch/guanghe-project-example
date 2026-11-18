# 光禾AI智能体框架 Python Agent 现有代码解析

> 历史基线，不是当前接入指南。本文描述 2026-08-28 的意图图架构；普通聊天现在走共享原生循环，
> 新增智能体先读 [当前扩展入口](../app/agents/README.md) 与 Java 仓库的 `docs/architecture/adding-an-agent.md`。

> 解析日期：2026-08-28
>
> 仓库：`D:\coding-project\guanghe-agent`
>
> 代码基线：`master@e40e9feefecb` 加当前未提交工作区

## 1. 文档定位

本文面向后续开发、代码评审和 Java/Python 联调人员，解释当前 Python Agent 仓库的目录职责、启动过程、核心调用链和已知边界。

当前代码可以概括为：

```text
一个有界 Commerce Agent 主图
  + GEO/IMAGE/VIDEO/ARTICLE 四个领域子图
  + Local/DeepSeek 模型网关
  + Chat Run API 与 SSE
  + Provider-neutral 长任务契约和 Mock
  + 独立本地 Worker 骨架
```

当前不是多 Agent 协作系统，而是一个主 Agent 根据意图将请求路由到不同领域子图。Java 仍是身份、权限、积分、价格、正式任务状态和业务结果的唯一真相源。

## 2. 技术栈

| 类型 | 当前使用 |
|---|---|
| Python | Python 3.12 |
| Web/API | FastAPI、Uvicorn |
| 数据校验 | Pydantic v2、pydantic-settings |
| Agent 编排 | LangGraph |
| HTTP Client | HTTPX |
| 配置文件 | YAML Prompt Bundle |
| 包和环境 | uv |
| 测试 | Pytest |
| 代码质量 | Ruff、Pyright |
| 模型 | DeepSeek Chat Completions Adapter、本地 deterministic gateway |

## 3. 目录结构与职责

```text
guanghe-agent/
├─ app/
│  ├─ api/                 Agent FastAPI 应用、接口和开发控制台
│  ├─ agent/               Chat Run 模型、主图、领域子图和运行管理器
│  ├─ model_gateway/       对话模型统一接口、Local Gateway、DeepSeek Adapter
│  ├─ providers/           长任务 Provider-neutral 契约和 Provider Mock
│  ├─ workers/             独立 Worker、Local/Rabbit Broker、Control Plane 边界
│  ├─ prompts/             Prompt Bundle 模型、加载器和 YAML Bundle
│  ├─ tools/               Agent 工具白名单及风险级别
│  ├─ harness/             Run 预算和 Java/Python 业务边界校验
│  ├─ security/            Internal HMAC、Redis Nonce 与请求验签
│  ├─ observability/       最小 JSON 日志
│  ├─ common/              配置、枚举和公共运行态
│  └─ cli.py               Agent API 命令行入口
├─ contracts/              OpenAPI、SSE、Rabbit 和 HMAC 契约
├─ evals/                  Agent 合成评测集和评测执行器
├─ tests/                  Contract 与 Unit 测试
├─ deploy/                 当前部署说明
├─ docs/                   阶段报告和代码解析文档
├─ pyproject.toml           依赖、命令入口和质量工具配置
└─ uv.lock                  锁定依赖版本
```

## 4. 两个独立进程

### 4.1 Agent API 进程

启动命令：

```powershell
uv run guanghe-agent-api
```

启动链路：

```text
pyproject.toml: guanghe-agent-api
  → app.cli.run_api()
  → Uvicorn 加载 app.api.main:app
  → app.api.application.create_app()
  → FastAPI lifespan 初始化运行态
```

lifespan 依次完成：

1. 配置 JSON 日志。
2. 读取并校验 Prompt Bundle。
3. 校验 Bundle 中的工具与运行时工具白名单一致。
4. 根据配置构建 Local 或 DeepSeek Model Gateway。
5. 创建 `ChatRunManager`。
6. 调用 Gateway `probe()` 探活。
7. 进程退出时停止未完成 Run 并关闭 HTTP Client。

核心文件：

- [`app/cli.py`](../app/cli.py)
- [`app/api/main.py`](../app/api/main.py)
- [`app/api/application.py`](../app/api/application.py)

### 4.2 Worker 进程

启动命令：

```powershell
uv run guanghe-agent-worker
```

启动链路：

```text
pyproject.toml: guanghe-agent-worker
  → app.workers.main.run_worker()
  → Uvicorn 加载 app.workers.api:app
  → app.workers.application.create_worker_app()
  → FastAPI lifespan 创建 WorkerRuntime
```

API 和 Worker 不共享 FastAPI lifespan，也不会因为启动 API 而隐式启动 Worker。两个进程默认分别监听 `127.0.0.1:8000` 和 `127.0.0.1:8010`。

当前 Worker Runtime 同时支持进程内 Broker 与 RabbitMQ 拉取消费。Rabbit 实现被动订阅 Java 已创建的 `zhixiaohe.agent.image`，使用 QoS 和手动 ACK/NACK/reject，不在 Python 中声明 Exchange、Queue 或 DLQ。Java Worker Control Plane 已按冻结 Internal OpenAPI 接入 Claim、Execution Context、Heartbeat、Execution Event 和 Result Artifact，并复用既有 Java HMAC 客户端；它不会回退到本地 Stub。

核心文件：

- [`app/workers/main.py`](../app/workers/main.py)
- [`app/workers/api.py`](../app/workers/api.py)
- [`app/workers/application.py`](../app/workers/application.py)

## 5. Agent API 接口

### 5.1 系统接口

| 方法与路径 | 作用 |
|---|---|
| `GET /health` | 只表示 API 进程存活 |
| `GET /internal/v1/health/ready` | 检查 Bundle 与 Model Gateway 是否就绪 |
| `GET /internal/v1/capabilities` | 返回版本、Bundle Hash、Prompt Hash、领域和 Provider 状态 |

readiness 与 liveness 被刻意分离：Bundle 或 Gateway 不可用时，进程仍可存活，但 readiness 返回 `503 NOT_READY`。

### 5.2 Chat Run 接口

| 方法与路径 | 作用 |
|---|---|
| `POST /internal/v1/chat-runs` | 创建一个有界、免费 Chat Run |
| `GET /internal/v1/chat-runs/{runId}` | 查询当前进程内的 Run 状态 |
| `GET /internal/v1/chat-runs/{runId}/events` | 订阅 SSE 事件 |
| `POST /internal/v1/chat-runs/{runId}/stop` | 停止当前 Chat Run |

SSE 支持 `Last-Event-ID`，客户端可以从指定事件序号继续读取。对外只暴露稳定事件，不泄露 LangGraph 内部状态。

主要事件：

```text
run.started
message.delta
interaction.required
task-draft.proposed
usage.reported
run.completed
run.failed
```

### 5.3 本地开发接口

| 方法与路径 | 作用 |
|---|---|
| `GET /dev/agent` | 返回 Agent 开发控制台 |
| `POST /dev/v1/runs` | 使用本地最小授权上下文创建 Run |

开发接口只在 local/test 环境开启；production 环境返回 404。

接口代码：

- [`app/api/routes/system.py`](../app/api/routes/system.py)
- [`app/api/routes/chat_runs.py`](../app/api/routes/chat_runs.py)
- [`app/api/routes/dev.py`](../app/api/routes/dev.py)

## 6. Chat Run 数据模型

核心模型位于 [`app/agent/models.py`](../app/agent/models.py)，全部使用 `extra="forbid"`，防止未定义字段静默进入运行时。

### 6.1 输入模型

`ChatRunRequest` 包含：

- `runId`：本次运行唯一标识；
- `conversationId`：对话标识；
- `userContextRef`：Java 提供的短期上下文引用；
- `message`：本次消息和附件 Asset ID；
- `trustedState`：Java 已授权的空间、商品、草稿或任务引用；
- `budget`：模型调用、工具调用、步骤、Token 和 deadline 上限。

本地模式暂时直接从 `trustedState` 构造 `AuthorizedRunContext`。生产模式必须替换为签名的 Java Context Client，不能信任浏览器直接传来的业务 ID。

### 6.2 输出模型

`RunOutcome` 只有三类结果：

| kind | 含义 |
|---|---|
| `MESSAGE` | 普通文本回答 |
| `INTERACTION` | 缺少上下文或参数，需要用户补充 |
| `TASK_DRAFT` | 仅向 Java 提议任务草稿参数 |

`TaskDraftProposal.proposalOnly` 固定为 `true`，并带有 Java 必须重新校验权限、Schema、价格和能力的警告。

## 7. Agent 主图执行流程

主图位于 [`app/agent/graphs/commerce.py`](../app/agent/graphs/commerce.py)。

```text
ChatRunRequest
  → RuntimeHarness 校验 deadline 和预算
  → load_context：加载已授权引用
  → normalize：规范化用户消息
  → route：Model Gateway 识别意图
  → 条件路由
      ├─ GENERAL → 生成普通回答
      ├─ AMBIGUOUS_CONFIRMATION → 要求明确确认对象
      ├─ GEO → GEO 子图
      ├─ IMAGE → IMAGE 子图
      ├─ VIDEO → VIDEO 子图
      └─ ARTICLE → ARTICLE 子图
  → validate：检查预算和业务边界
  → RunOutcome
```

意图枚举：

```text
GENERAL
GEO
IMAGE
VIDEO
ARTICLE
AMBIGUOUS_CONFIRMATION
```

“确认、开始、执行”等无法确定业务对象的输入被路由到 `AMBIGUOUS_CONFIRMATION`，Python 不会据此执行历史草稿或正式业务动作。

## 8. 四个领域子图

领域代码位于 [`app/agent/graphs/domains.py`](../app/agent/graphs/domains.py)。每个领域使用单独的 `TaskDomainGraph` 实例和 Pydantic 参数模型。

| 领域 | 上下文要求 | 主要参数 |
|---|---|---|
| GEO | `selectedSpaceId` | topic、audience、region、mode、questions |
| IMAGE | `selectedProductId` | scene、style、lighting、aspectRatio、count、negativePrompt |
| VIDEO | `selectedProductId` | scene、style、durationSeconds、aspectRatio、cameraMotion |
| ARTICLE | `selectedProductId` | topic、tone、language、outline、targetLength |

每个子图都执行两个步骤：

```text
inspect
  → 检查经营空间或商品上下文
  → 检查是否只有“生成图片”等过于宽泛的描述

compose
  ├─ 缺字段：返回 InteractionProposal
  └─ 字段齐全：调用 Gateway 建议参数并生成 TaskDraftProposal
```

模型返回的领域参数首先经过 Gateway 白名单过滤，再经过领域 Pydantic Schema 校验。未知字段会被移除，非法类型、长度和数值范围会被拒绝。

## 9. Runtime Harness 与工具边界

[`app/harness/runtime.py`](../app/harness/runtime.py) 在图执行前后实施技术边界：

- deadline 已过期则拒绝运行；
- 输入估算 Token 超限则拒绝；
- 在动作前限制模型调用数、工具调用数和图步骤数；
- 分开记录 `workflowStepCount`、`modelCallCount`、`contextLoadCount`、
  `toolCallCount` 和 `providerCallCount`，其中模型/Provider 重试逐次计数；
- 禁止 Python 输出 `confirmationToken`、`quotedPoints`、`pricingVersionId`、`chargeSucceeded` 等 Java 业务字段；
- 禁止 Python 处理 Java 所有的 `MEDIA` 类型。

[`app/tools/registry.py`](../app/tools/registry.py) 原地扩展为唯一的轻量 Registry、Policy
和 Executor。当前只开放一个不可产生业务副作用的工具：

```text
task_draft.propose    PURE_FUNCTION / PREPARE
```

补问由 Graph 直接组装为 `interaction.required`，不算工具调用。Bundle 和运行时工具元数据
必须完全一致；运行时在注册阶段即拒绝 `COMMIT` 工具，并只记录哈希和状态等安全观察字段。

## 10. Model Gateway 与 DeepSeek

模型统一协议位于 [`app/model_gateway/gateway.py`](../app/model_gateway/gateway.py)。Agent 图只依赖 `ModelGateway`，不直接依赖 DeepSeek SDK 或 URL。

`ModelGateway` 提供四项主要能力：

```text
probe()
classify_intent()
answer_general()
suggest_task_parameters()
```

### 10.1 LocalModelGateway

- 使用关键词执行确定性意图分类；
- 返回固定、无外部调用的本地回答；
- 用于 local/test 和自动化测试；
- production 环境禁止使用。

### 10.2 DeepSeekAdapter

代码位于 [`app/model_gateway/deepseek.py`](../app/model_gateway/deepseek.py)。

主要行为：

- 初始化时要求 credential 和 model；
- 通过 `GET /models` 探活；
- 使用 `POST /chat/completions`；
- 意图和任务参数要求 `json_object`；
- 关闭思考模式，避免不可控的长推理输出；
- 普通回答使用流式响应，并将 delta 交给 Chat Run SSE；
- 记录 Provider 输入/输出 Token 和延迟；
- 将认证、限流、5xx、超时、连接和非法响应转换为稳定安全错误码；
- 不把 Provider 原始响应、密钥或敏感 Header 写入对外错误。

Gateway 构建逻辑：

```text
MODEL_GATEWAY=LOCAL      → LocalModelGateway
MODEL_GATEWAY=DEEPSEEK   → DeepSeekAdapter
MODEL_GATEWAY=AUTO       → 密钥和模型齐全时选 DeepSeek，否则 local/test 选 Local
production + LOCAL       → 启动失败/NOT_READY
```

## 11. ChatRunManager

[`app/agent/run_manager.py`](../app/agent/run_manager.py) 管理单个 API 进程内的 Chat Run 生命周期。

职责包括：

- 拒绝重复 `runId`；
- 限制本地 Run 数量；
- 后台执行 Agent 图；
- 保存有序 SSE 事件；
- 将真实模型 delta 或 Mock 分片转换为 `message.delta`；
- 支持 Stop；
- 上报模型、Token、延迟和 Bundle 信息；
- 将内部异常转换为安全的 `run.failed` code；
- 关闭进程时停止剩余 Run 并关闭 Gateway。

当前 Run 状态保存在 `OrderedDict` 内存中。服务重启后状态丢失，因此它只能用于免费 Chat Run 和开发演示，不能代替 Java 的正式任务状态。

## 12. Prompt Bundle

Bundle 加载器位于 [`app/prompts/loader.py`](../app/prompts/loader.py)，模型位于 [`app/prompts/models.py`](../app/prompts/models.py)。

启动时会执行：

1. 检查文件存在且不超过 1 MiB。
2. 使用 `yaml.safe_load()` 解析。
3. 使用冻结的 Pydantic 模型校验 Schema。
4. 校验 Prompt、Model Policy、Tool 和 Profile 的交叉引用。
5. 校验 Commerce Profile 为每个领域恰好绑定一个 Playbook。
6. 禁止 Profile 声明 `MEDIA`。
7. 计算原始 Bundle Hash 和规范化 Prompt Hash。

默认 Bundle：[`app/prompts/bundles/commerce-assistant-v1.yaml`](../app/prompts/bundles/commerce-assistant-v1.yaml)。

`agent-bundle-v1.9.0` 使用分层装配：稳定核心层依次为身份与目标、真实性与权威边界、
对话决策协议、通用电商顾问方法、工具决策和回复风格；Intent Router V2 单独用于路由；
GEO、IMAGE、VIDEO、ARTICLE Playbook 只在进入对应领域后装配；Task Parameter Extractor
最后接收领域字段说明。运行时只展示 Bundle、Registry 和 Java 授权交集中的工具，动态
Context 再由 `model_messages_with_context` 放入明确的数据边界。历史消息仍保持原角色顺序，
当前用户消息按 message ID 去重。

Router 的 `ANALYZE/PLAN + COMPLEX`（以及确需多个证据源的 `STANDARD`）会进入唯一的
只读多步 Runtime。计划严格限制为 3～5 步，工具只允许读取当前 Run 的授权目录、对话记忆和
任务工作区，或在这些观察上做竞品比较与证据聚合；依赖、版本、风险、授权和预算都在执行前校验。
观察失败最多触发一次重规划，已完成步骤不会重跑，最终综合只接收白名单观察而不是隐藏思维链。

Bundle 失效不会让 `/health` 失败，但会使 readiness 返回 `NOT_READY`，并阻止 Chat Run Manager 创建。

## 13. Provider-neutral 长任务层

长任务 Provider 契约位于 [`app/providers/models.py`](../app/providers/models.py)，与对话 `ModelGateway` 分开。

统一模型包括：

- `TaskProviderRequest`；
- `TaskProviderResult`；
- `ProviderArtifact`；
- `ProviderUsage`；
- `TaskProviderError`；
- `SubmissionState`。

`SubmissionState` 是重试安全性的关键：

| 状态 | 含义 | Worker 行为 |
|---|---|---|
| `NOT_SUBMITTED` | 确认尚未向 Provider 提交 | 满足条件时允许受限重试 |
| `CONFIRMED` | 已确认提交或已产生结果 | 不重复提交 |
| `UNKNOWN` | 无法确认 Provider 是否收到请求 | 禁止盲目重试 |

本地 [`LocalTaskProviderMock`](../app/providers/mock.py) 为四个领域生成明确标注的合成 Artifact，并支持认证、限流、上游 5xx、超时、非法响应、安全拒绝和提交状态未知等故障注入。

## 14. Worker 执行流程

Worker 核心位于 [`app/workers/runtime.py`](../app/workers/runtime.py)。

```text
Rabbit 消息或 Local Broker Delivery
  → TaskExecutionMessage Schema 校验
  → 校验 taskType 和 Worker 路由
  → 向 Java Control Plane Claim
      ├─ Java 不可用：NACK + requeue
      ├─ DUPLICATE/TERMINAL：ACK，不重复调用 Provider
      └─ CLAIMED：取得 executionNo 和 executionToken
  → ACK 消息
  → 从 Java 获取 Execution Context
  → 上报 Heartbeat
  → 上报 TASK_PROGRESS
  → 调用 TaskProviderAdapter
  → 向 Java 回报结果或失败
```

关键原则是 **Claim-before-ACK**：只有 Java 已确认执行权后才 ACK，避免多 Worker 重复执行同一业务任务。

当前实现还包含：

- Worker 最大并发信号量；
- retryable + `NOT_SUBMITTED` 才允许的重试；
- 指数退避、随机抖动和总重试预算；
- 非法消息 reject；
- Worker 停止时先停止接单，再排空在途任务；
- 本地任务状态和运行统计接口。

`LocalMessageBroker` 和 `LocalWorkerControlPlane` 都是内存实现，仅用于测试和控制台演示。`RabbitMessageBroker` 复用同一 `WorkerRuntime`，负责传输与消息确认；`JavaWorkerControlPlane` 通过既有签名客户端取得 Java 执行权和授权快照，并只上报执行证据，业务终态仍由 Java 裁决。当前 IMAGE 闭环准备使用 deterministic Mock Provider，不调用 Qwen、不产生费用，也不写 PostgreSQL。

## 15. 配置与日志

### 15.1 配置

配置类位于 [`app/common/settings.py`](../app/common/settings.py)。所有变量使用 `GUANGHE_AGENT_` 前缀，嵌套配置使用双下划线：

```text
GUANGHE_AGENT_MODEL_GATEWAY
GUANGHE_AGENT_DEEPSEEK__CREDENTIAL
GUANGHE_AGENT_DEEPSEEK__MODEL
GUANGHE_AGENT_WORKER_CONSUMER_NAME
GUANGHE_AGENT_WORKER_BROKER
GUANGHE_AGENT_WORKER_CONTROL_PLANE
GUANGHE_AGENT_WORKER_PROVIDER
GUANGHE_AGENT_RABBITMQ_URL
GUANGHE_AGENT_RABBITMQ_QUEUE_NAME
GUANGHE_AGENT_RABBITMQ_PREFETCH_COUNT
GUANGHE_AGENT_RABBITMQ_CONNECTION_TIMEOUT_SECONDS
GUANGHE_AGENT_PROVIDER_MOCK__FAILURE_MODE
```

Provider 密钥和 `RABBITMQ_URL` 由 `SecretStr` 保存。`.env.example` 只放变量名和非敏感占位符，真实凭据必须放入被 Git 忽略的 `.env.local` 或服务器受控环境文件。Rabbit 队列拓扑由 Java/运维创建，Python 只按队列名被动消费。

API 和 Worker 的 host 类型只允许 `127.0.0.1` 或 `::1`，防止开发进程意外绑定公网网卡。

### 15.2 日志

[`app/observability/logging.py`](../app/observability/logging.py) 输出最小 JSON Envelope：

```text
timestamp
level
logger
message
traceId/taskId/agentRunId/userIdHash（存在时）
exceptionType（发生异常时）
```

当前没有完整 OpenTelemetry、LangSmith Trace 或跨进程 Span，只能视为基础结构化日志。

## 16. 契约文件

| 文件 | 作用 |
|---|---|
| [`contracts/agent-openapi.yaml`](../contracts/agent-openapi.yaml) | Java 调 Python 的 Internal API 契约 |
| [`contracts/events/chat-run-event-v1.schema.json`](../contracts/events/chat-run-event-v1.schema.json) | Chat Run 稳定事件 Schema |
| [`contracts/rabbit/task-execution-requested-v1.schema.json`](../contracts/rabbit/task-execution-requested-v1.schema.json) | Java 发起 Worker 任务的 Rabbit v1 Schema |
| [`contracts/hmac/hmac-v1-test-vector.json`](../contracts/hmac/hmac-v1-test-vector.json) | Java/Python 共同 HMAC 固定测试向量 |

固定向量用于持续验证 Java/Python 签名一致性；`app/security/` 已实现 HMAC Middleware、签名 Client 和 Redis Nonce Store。

## 17. 测试与评测

测试分为：

```text
tests/contract/   契约文件、Rabbit Schema、SSE、HMAC 向量
tests/unit/       API、图、DeepSeek、Mock、Worker、Bundle 和配置
evals/            版本化 Agent 合成评测集
```

具体测试数量随提交演进，以当前提交的 CI 或本地完整验证报告为准；本地 Agent 回归套件固定包含 36 条确定性用例。

本地完整评测：

```powershell
uv run python -m evals.run_agent_evals --mode local --suite regression
```

真实 DeepSeek Smoke 默认只选择 4 条用例，完整 live regression 必须显式增加 `--allow-full-live`，防止意外消耗模型额度。

## 18. 当前仍待完成的生产能力

1. RabbitMQ 拓扑发布、服务器联合 Gate 和 DLQ 运维验证；Python Consumer 只被动消费 Java 拥有的拓扑。
2. IMAGE Mock Worker 与 Java 冻结 Internal API 的服务器联合验证。
3. 真实任务 Provider 的端到端执行、资产持久化和付费闭环。
4. Run 和 Worker 状态的生产持久化。
5. Python Worker 的测试服务器 systemd 部署和恢复验证。
6. 完整分布式 Trace 和运行指标平台。

Worker 消息模型已按现有 Rabbit v1 Schema 严格校验十进制 ID、W3C `traceparent` 和 Python
拥有的任务类型；共享 Schema 的业务语义在 Java 协调前不得修改。

## 19. 推荐代码阅读顺序

新开发人员建议按以下顺序阅读：

1. [`README.md`](../README.md)：项目边界和启动方式。
2. [`app/common/settings.py`](../app/common/settings.py)：运行模式和 Provider 配置。
3. [`app/api/application.py`](../app/api/application.py)：API 初始化过程。
4. [`app/agent/models.py`](../app/agent/models.py)：输入、输出和事件模型。
5. [`app/agent/graphs/commerce.py`](../app/agent/graphs/commerce.py)：主图路由。
6. [`app/agent/graphs/domains.py`](../app/agent/graphs/domains.py)：四个领域子图。
7. [`app/model_gateway/gateway.py`](../app/model_gateway/gateway.py)：模型抽象。
8. [`app/model_gateway/deepseek.py`](../app/model_gateway/deepseek.py)：真实对话模型 Adapter。
9. [`app/agent/run_manager.py`](../app/agent/run_manager.py)：Chat Run 与 SSE 生命周期。
10. [`app/providers/models.py`](../app/providers/models.py)：长任务 Provider 契约。
11. [`app/workers/runtime.py`](../app/workers/runtime.py)：Worker 核心流程。
12. [`tests/`](../tests)：以测试确认每个边界的预期行为。

## 20. 常见扩展位置

### 新增领域

1. 在领域参数模型中定义严格 Schema。
2. 在 `DOMAIN_DEFINITIONS` 注册意图、任务类型、上下文要求和默认值。
3. 在 Gateway 参数白名单中登记允许字段。
4. 更新 Prompt Bundle、capabilities 和契约。
5. 补充单元测试和评测用例。

### 新增对话模型

1. 实现 `ModelGateway` Protocol。
2. 在统一构建函数中增加显式选择逻辑。
3. 映射安全错误码、Provider 状态和 Usage。
4. 禁止 Agent 图直接调用 Provider URL 或读取密钥。

### 新增长任务 Provider

1. 实现 `TaskProviderAdapter`。
2. 明确 `SubmissionState` 和重试安全性。
3. 返回统一 Artifact 和 Usage。
4. 通过 Worker 注入 Adapter，不在 Worker 中硬编码厂商协议。

### 接入 Java

1. 以冻结的 Java Internal OpenAPI 交叉审查 `JavaWorkerControlPlane` DTO。
2. 通过 `WORKER_BROKER=RABBITMQ` 装配现有 Rabbit Consumer；不在 Python 中复制拓扑定义。
3. 在测试服务器完成 IMAGE Mock Worker 的 Claim、授权上下文、进度与结果联合 Gate。
4. 保持 Java 负责鉴权、积分、正式任务和业务终态。

## 21. 本地运行与质量检查

```powershell
Set-Location 'D:\coding-project\guanghe-agent'
uv sync

uv run guanghe-agent-api
uv run guanghe-agent-worker
```

分别启动后可访问：

```text
Agent 控制台：http://127.0.0.1:8000/dev/agent
Worker 控制台：http://127.0.0.1:8010/dev/worker
```

提交前执行：

```powershell
uv run ruff check .
uv run ruff format --check .
uv run pyright
uv run pytest
uv run python -m evals.run_agent_evals --mode local --suite regression
git diff --check
```

## 22. 总结

当前仓库已经具备清晰的 API/Worker 进程边界、受限 Agent 主图、四个领域子图、统一模型接口、DeepSeek Adapter、Provider Mock、独立 Worker 骨架和自动化评测体系。

代码的核心安全思想是：Python 只负责智能分析、参数建议和 AI 执行，不取得 Java 的权限、积分和业务终态职责；所有本地 Stub 和 Mock 在生产环境都会明确返回未就绪，不允许伪装成真实链路。
