# ARTICLE 执行契约 v2 实现说明

状态：`IMPLEMENTED`。文件名保留 `proposal` 仅为历史兼容；Java Internal OpenAPI 是跨端传输
权威来源，Python `contracts/agent-openapi.yaml` 中的 ARTICLE Schema 是执行模型权威来源。
Python 只执行 Java 已授权的一个生成阶段，不创建正式任务、业务状态、确认或积分记录。

## 1. 阶段与输入

内核单次只执行 `TITLE`、`OUTLINE`、`BODY`、`REWRITE` 中的一个阶段，并校验阶段依赖：

- `TITLE`：接收 `articleId`、`brief`、`targetLength`、`tone`、可选 `targetPlatform` 和授权摘要，
  产出恰好 5 个唯一标题候选。
- `OUTLINE`：兼容 mode 名不变；另外要求 `selectedTitle`，产出结构化文章创作方案 `plan` 和
  与 `plan.sections` 完全一致的 `outline` 兼容镜像。方案包含 `summary`、`coreMessage`、
  `targetAudience`、`sections`、`tone`、`targetLength`、`targetPlatform` 和 `riskNotes`。
- `BODY`：另外要求 `selectedTitle`、完整 `confirmedPlan` 与 `confirmedOutline` 镜像；两者不一致
  时在调用模型前拒绝。正文块数量、顺序、key 和 heading 必须与已确认方案完全一致。
- `REWRITE`：另外要求 `rewriteInstruction`、`currentBody`、`currentVersionRef` 和
  `rewriteBlockIds`，同时遵守完整 `confirmedPlan`。模型只返回授权块的替换内容，Python 合并时
  保持其他块逐字不变。

授权输入还可以包含 `sourceAssetIds`、`geoReportId` 以及 Java 已清洗的经营空间、品牌、商品、
素材和 GEO 报告摘要。每个素材或报告 ID 必须有匹配摘要；Python 不据 ID 读取 OSS、数据库、
网页或报告。摘要作为不可信数据，不是可执行指令。

v2 已冻结：

- 一次执行的幂等键、`taskId`、`executionId`、`articleId`、阶段和当前文章版本引用；
- 上述阶段输入、授权摘要、完整方案及其长度上限；
- `maxModelCalls`、`maxRepairRetries`、`maxInputTokens`、`maxOutputTokens`、`deadlineAt`；
- Claim/恢复语义、ARTICLE 队列名、结果幂等键和稳定失败码；
- ARTICLE `snapshotSchemaVersion=2`、ARTICLE_CONTENT `schemaVersion=2`、`inlineData` 上限和
  未知字段拒绝策略。

Java 仍独占 URL、整体文章状态机、确认、积分和持久化语义。

## 2. ARTICLE_CONTENT 建议映射

Python 内部结果可映射为一个 `ARTICLE_CONTENT` 小型结构化 artifact：

```json
{
  "mode": "BODY",
  "titleCandidates": [],
  "title": "已选择标题",
  "plan": null,
  "outline": [
    {"key": "section-1", "sequence": 1, "level": 2, "heading": "小节标题", "brief": "本节写作要点"}
  ],
  "body": {
    "markdown": "## 小节标题\n\n正文",
    "blocks": [
      {"key": "section-1", "heading": "小节标题", "markdown": "正文"}
    ]
  },
  "provider": "DEEPSEEK",
  "model": "运行时模型名",
  "modelVersion": "chat-completions-v1",
  "promptVersion": "agent-bundle-v1.12.0",
  "usage": {
    "modelCallCount": 1,
    "inputTokens": 100,
    "outputTokens": 50,
    "latencyMs": 500,
    "monetaryCost": null,
    "currency": null
  },
  "warnings": [],
  "metadata": {
    "proposalOnly": true,
    "persisted": false,
    "published": false,
    "authorizedRewriteBlockIds": []
  }
}
```

结果不包含用户 ID、JWT、HMAC、确认令牌、价格、积分、支付/发布状态、Provider 原始响应、
Base64、预签名 URL 或数据库归属字段。`monetaryCost=null` 表示供应商没有在响应中提供可验证
金额，不表示免费。Java 负责验收 artifact、保存权威正文、版本冲突、报价确认与业务终态。

## 3. 对话连续性与当前文章锚点

文章生成和投稿是同一个有状态流程，不能依赖“文章卡片是否恰好是最后一条消息”。每个对话在任一时刻
只有一篇前台文章。Java 从该对话最近创建的 ARTICLE 草稿及权威文章表派生当前文章，并通过
`taskWorkspace.confirmedConstraints.activeArticle` 向 Python 提供只读锚点：

```json
{
  "activeArticle": {
    "articleId": "93272991927894016",
    "articleVersion": 3,
    "workflowStage": "PLAN_READY",
    "title": "已确认标题",
    "productId": "..."
  }
}
```

- 普通闲聊不会清空、覆盖或推进当前文章；用户之后仍可回到原流程。
- “继续文章、下一步、改标题、重做创作方案（兼容用户说‘提纲’）、推荐媒体、投稿”等承接表达，在没有待确认草稿或执行中任务时，
  仍由该锚点路由回 ARTICLE。
- 用户明确提出另一篇文章时，如果当前文章尚未完成投稿，Agent 必须先显示冲突提示，让用户选择继续当前文章
  或开始新文章；不得静默创建第二篇前台文章。
- 只有用户在该冲突交互中明确选择“开始新文章”，才允许创建新的 TITLE 草稿；新的文章成为该对话的前台文章。
- 当前文章完成一次媒体投稿任务后，连续流程结束；失败或尚未确认的投稿仍属于当前文章。
- Python 只能说明当前阶段或生成下一阶段草稿，不能代替业务确认流程确认标题、文章创作方案、正文或投稿。
- Web 必须在对话头部持续显示当前文章与阶段入口，允许用户在闲聊后定位回对应流程卡片。

## 4. 运行边界

- 所有文字请求仍经过现有 ModelGateway；DeepSeek 失败不切换 Qwen、LOCAL 或模板。
- 每个阶段只装配共享 Prompt 核心层、ARTICLE Playbook 与当前阶段指令。
- 每个真实 HTTP 请求及修复重试都计入 Usage；调用数、输入/输出 Token 与 deadline 均有界。
- Python 不执行确认、保存、投稿、发布或管理员审核，不写 PostgreSQL。
- Python Agent OpenAPI 已发布 `ArticleExecutionRequest`、`ArticleExecutionResult` 和完整方案组件；
  共享事件 Schema 与 RabbitMQ 路由保持不变。
