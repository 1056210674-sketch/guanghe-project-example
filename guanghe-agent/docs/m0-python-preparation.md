# Python M0 准备结果

> 检查日期：2026-08-28  
> 当前 Java 开发基线：`guanghe-backend@8a3a02bcde032a0cbae5c9efdc284cff57f5b0cb`
> Python 基线：`guanghe-agent@e40e9fe`（工作区另有未提交的后续阶段代码，不计入 M0）
>
> 最终联合 Gate 以 Java/Python 双 Commit 兼容矩阵为准，不能仅凭任一单仓库基线判定通过。

## 1. M0 边界

M0 的目标是由 Java 负责人完成并部署稳定的 Java 测试服务。Python 在本阶段不新增 Agent、模型、Worker 或业务功能，只完成内部接口、共同 HMAC 向量、Java 发起的 Rabbit Schema 和 Provider Gateway 补丁的阅读，登记疑问，避免提前形成假契约。

因此本文件不把当前 Agent 控制台、DeepSeek、多领域子图、Provider Mock 或 Worker 视为 M0 交付，也不将 `PYTHON_M1_READY` 标记为 `true`。

## 2. 已核对的 Java 通信约定

### Java 调用 Python

- `GET /internal/v1/health/ready`：不签名，仅允许 loopback 使用。
- `GET /internal/v1/capabilities`：必须使用 HMAC v1 签名。
- Java Client 的目标地址固定为 `http://127.0.0.1:8000`。
- Java Client 连接失败、超时、HTTP 错误或非法 JSON 时返回安全的 `UNAVAILABLE` 状态，不影响 Java 自身 readiness。
- 出站调用延续 W3C `traceparent` 的 trace ID，并生成新的 span ID。

### Python 调用 Java

- dev/test/mock 反向探针为 `POST /internal/v1/test/reverse-call`。
- readiness 以外的 `/internal/v1/**` 都要求 HMAC v1。
- 验签 Header：`X-Internal-Key-Id`、`X-Internal-Timestamp`、`X-Internal-Nonce`、`X-Content-SHA256`、`X-Internal-Signature`。
- canonical string 为五行 UTF-8 文本：timestamp、nonce、大写 method、原始 path/query、body SHA-256 小写十六进制摘要；行间使用 `\n`，末尾不追加换行。
- timestamp 默认窗口为正负 60 秒；Nonce 必须是规范的小写 UUID。
- 签名通过后，Java 才使用 Redis `SET NX + TTL` 认领 Nonce；TTL 至少 120 秒；Redis 不可用时 fail closed。
- 反向探针成功结果包含 `accepted`、`callerKeyId` 和 `traceId`。

## 3. 已同步的共同契约

- `contracts/hmac/hmac-v1-test-vector.json` 已与 Java 的共同固定向量内容一致（工作区仅行尾格式不同）。
- `contracts/rabbit/task-execution-requested-v1.schema.json` 已与 Java 发起的 Schema 内容一致（工作区仅行尾格式不同）。
- Rabbit `traceparent` 是必填的非空 W3C 格式字符串，不允许 `null`。
- Rabbit 中所有业务 ID 都是十进制字符串；TaskType 仅允许 `GEO`、`IMAGE`、`VIDEO`、`ARTICLE`，`MEDIA` 不投递 Python。
- Rabbit 消息不携带 Prompt、正文、Base64、JWT、凭据或 Provider Key。
- Python 契约测试按 Java 的五行 canonical 规则独立重算 SHA-256 和 HMAC，避免依赖向量中预先保存的 canonical 文本。

## 4. 已阅读的 Provider Gateway 补丁

已完整阅读 `ARCH-PATCH-001 v1.0`（SHA-256：`4A61CC37FCFC573D4E4E32C3A1BB3D795DBF3E8049B1F6BF65AAF46C3482A8CA`），确认以下约束：

- Provider Gateway 是 Python 进程内统一模块，不是独立部署服务；第一版不部署 New API 或 Higress。
- Agent、LangGraph 节点和 Worker 不得直接调用供应商；模型、搜索、生图和生视频都必须经过 Gateway。
- GEO 七个平台是七条独立采样链路，禁止跨平台 Fallback 或冒充原平台。
- Agent 节点只引用 `reasoning-primary`、`multimodal-primary` 等内部能力别名，不散落真实模型 ID。
- 生图和生视频采用异步 Adapter；结果未知时先查单，禁止重复提交副作用任务。
- Gateway 统一负责连接池、超时、限流、重试、熔断、错误分类、用量、成本和 Trace，但 Java 仍是鉴权、积分、任务状态和业务结果的唯一真相源。
- 每次调用使用稳定 `logicalCallId`，保存真实 Provider/模型和脱敏诊断信息；不得记录密钥、完整敏感正文或敏感请求头。
- PGW-1 才建立 `contracts/registry/executor` 和 Mock Provider，属于 M0 之后的开发，不在本次补丁阅读中提前实现。

当前扫描显示，外部 HTTP 调用只出现在未提交的 `app/model_gateway/deepseek.py` Adapter 中，Agent 节点通过 Gateway 协议调用，没有在节点内直接拼接 Provider URL。现有草稿仍拆分为 `app/model_gateway` 和 `app/providers`，尚未形成补丁建议的统一 `provider_gateway` 治理模块；该草稿不计入 M0 验收，进入 PGW-1 时再按补丁评审和收敛。

## 5. 不在 M0 实现的 M1 项目

- Python HMAC Server 中间件。
- Python 调 Java 的 HMAC Client/Stub。
- Redis TLS Nonce 防重放。
- `traceparent` 跨服务延续实现。
- 将 `/internal/v1/capabilities` 实际置于 HMAC 保护下。
- `PYTHON_M1_READY=true` 和 Python 测试服务器部署。

这些项目只能在 `JAVA_M1_SERVER_READY=true`、`M0_GATE=PASS` 后进入 M1。

当前工作区已有未提交的后续阶段 Worker 草稿，其中 `TaskExecutionMessage` 仍允许 `traceparent=None`、非十进制 ID 和 `MEDIA` 枚举。它不属于 M0，本次不继续修改；进入对应里程碑前必须按已同步的 Java Schema 修正并补兼容测试。

## 6. 待负责人确认的问题

1. Java 仓库当前没有版本化的 Java Internal OpenAPI 文件，只有 Controller 和测试代码。M1 开始前请确认由 Java 提供 OpenAPI，或明确批准 Python 按哪个 Commit 生成 Client/Stub。
2. 请提供 M0 Gate 通过后的测试 HTTPS API 地址、测试服务器 Java Commit 和 Gate 证据；这些信息未出现前，Python 不开始真实双向 HTTP 联调。
3. 共同 HMAC 向量中的 Secret 仅用于固定向量测试，长度短于运行环境要求的 32 UTF-8 字节。请确认继续保留该测试特例；真实环境必须使用独立且至少 32 字节的 Secret。

## 7. 当前状态

```text
PYTHON_M0_CONTRACT_REVIEW=COMPLETE
PYTHON_M0_CONTRACTS_ALIGNED=true
PROVIDER_GATEWAY_PATCH_AVAILABLE=true
PROVIDER_GATEWAY_PATCH=ARCH-PATCH-001/v1.0
PYTHON_M1_READY=false
```

Python 侧 M0 契约阅读、共同契约对齐和疑问登记已经完成。`PYTHON_M1_READY` 仍必须保持 `false`，等待 Java 测试服务通过 M0 Gate 后再进入 M1。
