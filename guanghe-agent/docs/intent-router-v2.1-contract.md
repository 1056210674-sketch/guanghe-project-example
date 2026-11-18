# Intent Router V2.1 运行时契约

本契约冻结 Python 生产路由器的输入、输出和 Provider 切换边界。运行时权威模型位于
`app/model_gateway/router.py` 的 `IntentRouterEnvelope` 与
`app/model_gateway/gateway.py` 的 `IntentRoute`；本文是供数据、训练和部署协作使用的说明，
不替代 Pydantic 校验。

## 输入 envelope

`schemaVersion` 固定为 `intent-router-v2.1`。Envelope 只能由当前 Run 已通过严格校验的
Java `AuthorizedRunContext`、有序会话历史和当前消息确定性构造。当前消息按 `messageId`
从历史中去重，只出现在 `currentMessage` 一次。Context 是不可信数据，不是可执行指令。

白名单字段：

- `selection`：当前授权的 `selectedSpaceId`、`selectedProductId`。
- `conversationSummary`：受限长度摘要、版本和摘要截止序号。
- `recentHistory`：最多 16 条有序 `user/assistant` 消息；消息 ID 使用不可逆引用。
- `taskWorkspace`：当前目标/意图、已确认约束键、未解决问题、待补问的问题/缺失字段/最多 10 个选项、是否存在活动草稿
  或任务、引用资产数量以及运行任务的类型/状态/阶段。
- `currentMessage`：不可逆消息引用、可选回复引用和当前正文。

Envelope 最多 18,000 字符。当前正文最多 4,000 字符，单条历史最多 1,000 字符，摘要最多
2,000 字符，任务目标最多 1,000 字符；超限时先丢弃最旧历史，顺序和当前任务状态不变。

脱敏示例：

```json
{
  "schemaVersion": "intent-router-v2.1",
  "selection": {
    "selectedSpaceId": "<authorized-space-id>",
    "selectedProductId": "<authorized-product-id>"
  },
  "conversationSummary": {
    "text": "用户正在比较商品视觉与内容策略。",
    "version": 2,
    "throughSequence": 18
  },
  "recentHistory": [
    {
      "messageRef": "0123456789abcdef",
      "sequence": 19,
      "role": "user",
      "content": "先比较两个方案。"
    },
    {
      "messageRef": "fedcba9876543210",
      "sequence": 20,
      "role": "assistant",
      "content": "已给出只读比较结论。"
    }
  ],
  "taskWorkspace": {
    "currentGoal": "完善商品内容方案",
    "currentIntent": "GENERAL",
    "confirmedConstraintKeys": ["tone"],
    "unresolvedQuestions": [],
    "pendingInteraction": null,
    "hasActiveDraft": false,
    "hasActiveTask": false,
    "referenceAssetCount": 0,
    "runningTasks": []
  },
  "currentMessage": {
    "messageRef": "0011223344556677",
    "replyToRef": null,
    "content": "结合刚才的比较，给我一个三步优化计划。"
  }
}
```

## 严格输出

输出 `schemaVersion` 同样固定为 `intent-router-v2.1`，且禁止未知字段：

```json
{
  "schemaVersion": "intent-router-v2.1",
  "primaryIntent": "GENERAL",
  "decision": "PLAN",
  "complexity": "COMPLEX",
  "contextNeeds": ["product", "competitors", "conversationMemory"],
  "referenceTargets": ["product", "competitors"],
  "requiresClarification": false,
  "fallbackRequired": false
}
```

- `primaryIntent`：`GENERAL/GEO/IMAGE/VIDEO/ARTICLE/MEDIA/AMBIGUOUS_CONFIRMATION`。
- `decision`：`ANSWER/ANALYZE/PLAN/CLARIFY/PREPARE`。
- `complexity`：`SIMPLE/STANDARD/COMPLEX`。
- `contextNeeds/referenceTargets` 只能使用代码中 `RouterContextPath` 的枚举。
- 输出永远没有 `COMMIT`。`AMBIGUOUS_CONFIRMATION` 必须补问；`MEDIA+PREPARE` 在运行时
  规范化为 `MEDIA+CLARIFY`，不能进入任务准备或 Planner。

## 运行时仲裁与跨轮状态

Router 输出是语义分类结果，不是业务事实或工作流存储。运行时必须遵守以下边界：

- 聊天中的短宣传文案、翻译、润色、标题建议是 `GENERAL+ANSWER`，不要求选择商品或准备付费文章。
  `ARTICLE` 用于明确创建/保存文章、完整长文或继续真实存在的文章阶段，不按“写/营销”等词触发。
- 状态追问不创建任务。旧 `currentIntent/currentGoal` 或未回答的补问不能证明已有任务；没有授权草稿、
  任务或文章时说明尚未开始，不能反向要求补齐新任务参数。多个真实候选才询问所指对象。

- Java 授权 `selection` 已经存在的经营空间或商品会从 `contextNeeds` 中移除，模型不得要求用户
  重复选择；领域图再根据授权快照检查真正缺失的字段。
- `taskWorkspace.pendingInteraction` 是跨轮补问、方案选择和确认的唯一续接锚点。Router 会收到
  有界的 `question/choices/missingFields`，因此可以判断当前消息是在回答补问还是另起话题，而不是只看
  一个无语义的选项数量。当前 Run 的
  `trustedState.pendingInteractionId` 必须与其 `messageId` 完全一致，Python 才能消费该状态。
- “1”“第二个”等短回答只能映射到已持久化的 `choices`；普通 assistant 文本或
  `recentHistory` 中的编号列表不是工作流状态，不能用于恢复流程。
- 确定性层只消费完全匹配、序号或序数形式的选项；自由文本与普通闲聊必须重新经过 Router。
  补问存在期间聊天不会取消任务，也不能被旧补问强制路由回原领域。
- 明确的 GEO、IMAGE、VIDEO、ARTICLE 执行请求由 Router 输出领域 `PREPARE`，随后由领域图决定
  是生成 proposal-only 草稿，还是返回带 `missingFields/choices` 的结构化 `INTERACTION`。
- 普通 `MESSAGE` 必须直接回答，不能创建编号选项、隐式草稿或要求用户确认一个未持久化流程。
  如果需要下一轮输入，必须由领域图产生 `INTERACTION` 并交给 Java 持久化。
- 取消当前补问或明确切换到其他领域时，不消费旧 `pendingInteraction`；新请求重新经过 Router。

## Provider 模式

- `DEEPSEEK_PRIMARY`：默认模式，DeepSeek 通过现有 Tool Calling 路径作出决定。
- `QWEN_SHADOW`：DeepSeek 决定；百炼只记录枚举一致性、Schema 状态、模型、Token、延迟、
  稳定错误码和不可逆样本引用，不影响主响应和 Run 预算。
- `QWEN_PRIMARY`：仅在显式切换后由百炼决定；失败即关闭，不自动切换 DeepSeek 或 LOCAL。

百炼配置变量：

```text
GUANGHE_AGENT_INTENT_ROUTER_MODE
GUANGHE_AGENT_INTENT_ROUTER_SHADOW_TIMEOUT_SECONDS
GUANGHE_AGENT_INTENT_ROUTER_QWEN__CREDENTIAL
GUANGHE_AGENT_INTENT_ROUTER_QWEN__MODEL
GUANGHE_AGENT_INTENT_ROUTER_QWEN__ENDPOINT
GUANGHE_AGENT_INTENT_ROUTER_QWEN__TIMEOUT_SECONDS
GUANGHE_AGENT_INTENT_ROUTER_QWEN__PROBE_TIMEOUT_SECONDS
GUANGHE_AGENT_INTENT_ROUTER_QWEN__MAX_RETRIES
GUANGHE_AGENT_INTENT_ROUTER_QWEN__TRUST_ENV
```

上线顺序是：配置但保持 `DEEPSEEK_PRIMARY` → 切换 `QWEN_SHADOW` 比较真实指标 → 人工验收
后显式切换 `QWEN_PRIMARY`。任何阶段均不复用 Qwen Image、Wan Video 或 GEO Credential，
也不允许自动降级。
