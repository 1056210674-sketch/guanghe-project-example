# 光禾AI智能体框架 Python 仓库协作规则

本文件适用于本仓库全部目录。除非负责人在当前任务中明确覆盖，所有 AI 和开发人员都应遵守。

## 1. 仓库职责

- Python 是有界智能执行面，负责意图识别、规划、受控工具调用、Provider 适配、流式事件和媒体生成。
- Java 是业务控制面和 PostgreSQL 唯一写入方，负责用户、权限、经营数据、任务、积分、确认和审计。
- Python 不直连业务 PostgreSQL，不持有 JWT，不裁决用户权限，不扣积分，不创建正式付费任务。
- Web 不直接访问 Python；所有浏览器业务请求必须先经过 Java。
- `task-draft.proposed` 永远只是提议，COMMIT 类动作必须回到 Java 确认和执行。
- PostgreSQL 是业务真相源；Redis、内存状态、LangGraph 状态和 Provider 返回均是可丢弃或可重建数据。

## 2. 开工前必须执行复用检查

修改前先：

1. 检查 `git status --short`、当前分支和 HEAD，保护无关用户改动。
2. 阅读 `README.md`、相关契约、Prompt Bundle、现有实现和相邻测试。
3. 使用 `rg` 搜索相同 Provider、Client、模型、Schema、错误码、重试、Trace 和测试。
4. 先形成简短复用计划：直接复用什么、扩展什么、为什么必须新增。

不得因为当前任务只涉及一个入口，就创建第二套 Gateway、HTTP Client、Harness、Run Manager、日志或配置体系。

## 3. 现有能力地图

优先从以下位置复用：

- Agent 状态、领域参数和有界图：`app/agent`
- Run 隔离和生命周期：`app/agent/run_manager.py`
- Runtime Harness、预算和工具上限：`app/harness/runtime.py`
- 文字模型统一入口：`app/model_gateway`
- Java Internal HTTP 客户端：`app/integration/java_internal.py`
- Java 授权 Run Context：`app/providers/java_context.py`
- 图片、视频和 Worker Provider：`app/providers`、`app/workers`
- HMAC 和 Nonce：`app/security`
- Trace 和结构化日志：`app/observability`
- 配置及运行态装配：`app/common/settings.py`、`app/common/runtime.py`
- Prompt Bundle：`app/prompts/bundles`
- Agent OpenAPI 权威文件：`contracts/agent-openapi.yaml`
- 共享事件和 RabbitMQ Schema：`contracts/events`、`contracts/rabbit`
- 回归评测：`evals`

新增通用抽象前必须确认至少有两个真实使用方。不要新建泛化 `BaseProvider`、`Utils`、第二套 Router 或第二套重试框架来解决一个 Provider 的局部差异。

## 4. 强制边界

- 所有文字模型调用必须经过 `app/model_gateway`，不得在 Graph、Route 或 Tool 中直接使用 `httpx` 调供应商。
- Java 调用只能复用 `JavaInternalClient`；不得新建绕过 HMAC、Nonce、Timestamp 和 Trace 的客户端。
- 测试服务器和生产环境的授权上下文来自 `JavaRunContextProvider`；本地 Stub 只能在显式 local/test 场景使用，不能成为生产降级路径。
- 授权 Context 必须逐 Run 获取，不跨 Run、用户或会话缓存。
- Runtime Validator 只校验格式、Schema、预算和安全合法性；业务权限、积分、定价和任务决策归 Java。
- 模型输出必须经过 Pydantic/JSON Schema 和字段白名单；模型不能生成或覆盖 `userId`、权限、价格、积分、确认令牌或发布状态。
- Provider 异常只向外暴露稳定错误码；不记录或返回上游原始错误正文、Prompt、密钥、Authorization 或用户敏感内容。
- 不保存完整 Provider 原始响应作为默认业务结果；确需证据时只保存经过 Schema 校验和脱敏的必要字段。
- Redis 仅用于 Nonce、限流或可重建缓存，不能成为任务状态真相或本地队列替代品。
- 长任务通过既有 Worker/RabbitMQ 契约执行；不要在 FastAPI 生命周期中启动另一套后台调度体系。

## 5. Provider 与 Prompt 规则

- 普通 AI 对话主模型为 DeepSeek；测试服务器必须显式选择，失败时不得静默切换 Qwen 或 LOCAL。
- Qwen Image、Wan Video 和未来 GEO Provider 使用各自独立适配器、配置和 Gate，不与文字主模型混用。
- 新 Provider 必须实现现有统一接口、稳定错误映射、超时、有限重试、用量统计和真实 Gate。
- Endpoint、模型名和响应结构必须通过当前官方资料或真实接口验证，不能照搬旧项目常量。
- Prompt Bundle 是版本化只读配置；修改内容必须升级版本并更新相关测试，不允许运行时静默改写 Prompt。
- 不把 Prompt 从数据库按请求重复加载；进程加载已发布 Bundle，版本切换通过明确发布完成。
- 用户智能体身份与运行 Bundle 分层：身份只取逐 Run 的 Java 授权快照，按已部署的 `agentKey + agentVersion` 静态注册选择执行适配器；注册表和 Prompt 在进程内只读，不接受浏览器、模型、资料或在线可变配置覆盖。缺失身份仅走明确的旧 GEO 兼容路径，未知身份/版本拒绝运行，能力取已发布集合与 Java 本轮授权的交集。

## 6. 契约规则

- `contracts/agent-openapi.yaml` 是 Python Agent API 权威来源，Route、模型和契约测试必须同步。
- SSE 必须声明并实际返回 `text/event-stream`；事件顺序、终态和断流语义必须测试。
- 共享 RabbitMQ/Event Schema 未协调 Java 前不得改变业务语义。
- 共享契约不得人工选择性复制；同步时使用完整文件、来源 Commit 和 LF SHA256。
- 不只验证文件能解析，必须验证关键状态码、媒体类型、字段上限、未知字段拒绝和错误码。

## 7. 验证

通常执行：

```powershell
uv sync
uv run ruff check .
uv run ruff format --check .
uv run python -m pyright
uv run pytest
uv run python evals/run_agent_evals.py
git diff --check
```

- 如果 Windows 包装器受策略阻止，允许使用等价的 `python -m pyright`，但必须在报告中说明。
- 回归评测默认使用确定性模型并保持零付费 Token；真实 Provider Gate 必须由任务明确授权。
- 不为通过测试而放宽全局超时、并发、安全校验、Schema 或预算。
- 除非任务明确要求，不在开发机启动长期 FastAPI/Worker 监听；真实联调在测试服务器进行。

## 8. 提交、部署与敏感信息

- 密钥、密码、Token、HMAC Secret 和服务器凭据只能进入受控服务器环境文件，不得进入 Git、日志、测试报告或最终回复。
- `.env.example` 和 README 只能写变量名或占位符。
- 不读取或导入旧项目整份生产环境；只按当前任务读取被授权的具体变量。
- 未经当前任务明确要求，不 Push、不部署、不调用真实模型、不产生费用。
- 禁止 force push、擅自 rebase 已共享历史或清理无关工作区改动。
- 若任务允许部署，报告本地、`origin/master`、服务器运行 Commit、Provider 身份、调用次数和 systemd 状态。

## 9. 完成报告

报告必须包括：

- 直接复用和扩展的现有能力。
- 新增文件及无法复用的具体原因。
- Prompt/Provider/契约变化。
- 测试、评测、Token 和真实费用结果。
- 完整 Commit SHA、工作区状态、是否 Push/部署。
- 明确说明未触碰的仓库、数据库和业务范围。
