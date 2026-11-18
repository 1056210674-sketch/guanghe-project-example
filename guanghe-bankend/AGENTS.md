# 光禾AI智能体框架 Java 仓库协作规则

本文件适用于本仓库全部目录。除非负责人在当前任务中明确覆盖，所有 AI 和开发人员都应遵守。

## 1. 仓库职责

- Java 是业务控制面，也是 PostgreSQL 业务数据的唯一写入方。
- Java 负责登录鉴权、用户归属、经营资料、会话与任务真相、积分、确认令牌、审计和浏览器 API。
- Web 只访问 Java Browser API，不直接访问 Python。
- Python 是智能执行面；Java 通过版本化 Internal API、SSE 和 RabbitMQ 与其通信。
- PostgreSQL 是业务真相源。Redis、RabbitMQ 消息和 Agent 运行缓存均不得替代数据库状态。
- 已确认的付费任务不支持取消；免费对话 Run 的停止能力与付费任务取消是两件不同的事。

## 2. 开工前必须执行复用检查

修改前先：

1. 检查 `git status --short`、当前分支和 HEAD，保护无关用户改动。
2. 阅读 `README.md`、相关 OpenAPI/JSON Schema、现有实现和相邻测试。
3. 使用 `rg` 搜索相同业务名、接口、错误码、DTO、Mapper、Client、配置和测试。
4. 先形成简短复用计划：直接复用什么、扩展什么、为什么必须新增。

不得因为没有在当前文件看到实现，就直接创建第二套基础设施。

## 3. 现有能力地图

优先从以下位置复用：

- 通用响应、错误码和异常处理：`src/main/java/com/guanghe/backend/common/api`
- Web 参数和请求公共能力：`src/main/java/com/guanghe/backend/common/web`
- ID 规则：`src/main/java/com/guanghe/backend/common/id`
- 安全、JWT 和账号状态：`src/main/java/com/guanghe/backend/auth/security`、`auth/account`
- Redis、RabbitMQ、Jackson 等配置：`src/main/java/com/guanghe/backend/common/config`
- 经营空间、商品、竞品：`src/main/java/com/guanghe/backend/catalog`
- 会话、消息、Run、事件和 Context 快照：`src/main/java/com/guanghe/backend/conversation`
- Java/Python HMAC、Nonce、Trace 和客户端：`src/main/java/com/guanghe/backend/integration/internal`
- Browser 契约：`src/main/resources/contracts/openapi/browser-openapi.yaml`
- Java Internal 契约：`src/main/resources/contracts/openapi/java-internal-openapi.yaml`
- Python Agent 契约镜像及来源清单：`src/main/resources/contracts/openapi/agent-openapi.yaml`、`agent-openapi-source.json`
- RabbitMQ/SSE 契约：`src/main/resources/contracts/rabbit`、`src/main/resources/contracts/sse`
- Flyway：`src/main/resources/db/migration`

新增通用抽象前必须确认至少有两个真实使用方。不要创建新的 `Utils`、`BaseService`、HTTP Client、响应包装器或状态机来解决单一局部问题。

## 4. 强制边界

- 不在 Java 中调用具体模型供应商；模型和媒体生成调用属于 Python Provider 层。
- 不让浏览器传入或决定 `userId`、角色、内部权限、积分结果或数据库归属。
- 不绕过现有 Security、HMAC、Nonce、Trace、统一异常处理和 Python Internal Client。
- 不重复实现 Redis/RabbitMQ/PostgreSQL 连接、重试、序列化或健康检查。
- 普通 CRUD 不因“保险”而强制增加 `Idempotency-Key`；仅对付费、投递、支付、采购等确有重复副作用的动作使用幂等。
- JSON 中 Snowflake ID 必须保持字符串语义。
- 权限过滤必须在服务端完成；查询、更新和删除都必须验证用户归属。
- 任务状态、积分和确认结果必须以数据库事务结果为准，不能从缓存或模型输出推断。
- Python 返回的 `task-draft.proposed` 只是草稿，不得直接扣费、创建正式任务或投递执行消息。
- 单次 GEO/MONITOR_RUN 的另一路径由 Java 独立准入：原 USER 明确 executionIntent、有效预授权、冻结输入与预算全部通过，且对话 Run 已 COMPLETED 后才受理；审计为 EXECUTION_PREAUTH/ADMITTED，不伪造用户点击或确认时间。Python proposal 本身仍不能扣费。
- 不把业务规则塞进通信层、序列化层或通用 Validator。

## 5. 数据库规则

- 共享测试库已执行的 V001、V002、V003 不得修改；后续变更追加 V004 及以后迁移。
- 只能用 Flyway 迁移结构，不在应用启动时建表，不手工复制 SQL 绕过 Flyway。
- Flyway 账号负责 DDL；Java Runtime 账号只负责业务 DML。
- 新迁移先在名称以 `zhixiaohe_gate_` 开头的隔离数据库验证，再由明确任务授权应用到共享测试库。
- 删除、级联、唯一性、归属和并发约束优先在数据库中建立可验证约束，同时保留清晰业务错误。

## 6. 契约规则

- Controller、DTO、OpenAPI 和契约测试必须在同一任务中保持一致。
- Browser API 以 Java `browser-openapi.yaml` 为权威来源。
- Java Internal API 以 Java `java-internal-openapi.yaml` 为权威来源。
- Python Agent OpenAPI 必须从 Python 权威文件完整同步，并更新来源 Commit 和 LF SHA256；禁止选择性手抄。
- 共享 RabbitMQ/SSE Schema 的业务语义和哈希不得在未协调另一端时擅自修改。
- 不只测试 YAML/JSON 可解析；必须测试关键路径、媒体类型、状态码、必填字段、枚举和错误响应语义。

## 7. 验证

本地使用仓库规定的干净构建入口：

```powershell
.\scripts\build.ps1 -JdkHome 'D:\JAVA\jdk'
```

- 需要时补充定向测试，但最终必须执行干净构建。
- 外部或破坏性 Gate 默认关闭，只有任务明确要求且目标被严格校验时才显式执行。
- 不为消除测试失败而放宽断言、全局超时、安全校验或契约要求。
- 除非任务明确要求，不在开发机启动长期运行的 Spring 服务；真实联调在测试服务器进行。

## 8. 提交、部署与敏感信息

- 密钥、密码、Token、真实 JDBC 地址和私钥只能进入受控服务器环境文件，不得进入 Git、日志、测试报告或最终回复。
- `.env.example`、YAML 示例和 README 只能写变量名或占位符。
- 未经当前任务明确要求，不 Push、不部署、不迁移共享数据库、不改写历史。
- 禁止 force push、擅自 rebase 已共享历史或清理无关工作区改动。
- 若任务允许部署，报告本地、`origin/master`、服务器运行 Commit 以及 systemd 状态。

## 9. 完成报告


报告必须包括：

- 直接复用和扩展的现有能力。
- 新增文件及无法复用的具体原因。
- 契约或迁移变化。
- 测试命令、测试数量和结果。
- 完整 Commit SHA、工作区状态、是否 Push/部署。
- 明确说明未触碰的仓库、环境和业务范围。
