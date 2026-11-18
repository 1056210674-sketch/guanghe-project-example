# 光禾AI智能体框架运行时边界

本项目采用“Java 业务控制面 + Python 智能执行面”，但不是两个可以互相决定业务状态的后端。
目标是让一次故障可以先按责任域定位，而不是在浏览器、Java、Python 三处同时猜测。

账号只有 USER/ADMIN，项目用于同一用户管理不同客户，不引入组织成员。
当前扩展和版本规则见 [架构说明](architecture/current-architecture.md)。

## 唯一权威

| 能力 | 唯一权威 | 说明 |
| --- | --- | --- |
| 登录、账号、角色、数据权限 | Java | 浏览器和 Python 不自行推导权限 |
| 客户项目、品牌资料、商品、素材、文章、媒体的 CRUD | Java | PostgreSQL 只允许 Java 写入；历史 business_space 是物理表名 |
| 草稿、确认、积分、任务、订单、投稿状态 | Java | 所有副作用在 Java 事务中完成 |
| 用户意图理解、内容规划、文本生成 | Python | 只输出回答或 proposal，不落业务状态 |
| 图片、视频、GEO、文章的模型/供应商执行 | Python Worker | 只消费 Java 已确认的冻结快照 |
| 页面状态展示 | Web | 只展示 Java Browser API 与 SSE 的权威状态 |

## 允许的调用

```text
Browser ── Browser API / SSE ──> Java
Java ── 单次上下文快照 + 流式运行 ──> Python Agent
Python Agent ── 已授权资料读取/工具动作请求 ──> Java Internal API
Java ── Rabbit 任务事件 ──> Python Worker
Python Worker ── claim / heartbeat / event / result ──> Java Internal API
```

禁止 Browser 直连 Python，禁止 Python 连接业务 PostgreSQL。Python 可通过受控工具请求 Java
创建任务，但不能自行确认业务状态、扣积分、绕过用户原文授权或决定投稿订单状态。
新 Run 携带 executionRelease/catalogHash；工具、资源读取和摘要沿用同一冻结/当前权限交集，
不能因为内部请求已签名就忽略智能体的能力边界。

## 确定性动作

“确认当前草稿”“继续当前步骤”“放弃草稿”“选择标题/提纲”“确认正文”“提交投稿”等动作已有明确
业务对象和状态机，不需要模型理解。它们必须直接调用 Java 的类型化动作端点。

聊天中的当前草稿确认使用：

```text
POST /api/v1/conversations/{conversationId}/actions/confirm-active-task-draft
```

Java 只在当前会话恰好存在一个未过期待确认草稿时执行，并在同一确认事务内重新校验所有权、
草稿状态、价格版本、可用积分和幂等键。该调用不会创建 Agent Run，也不会访问 Python。

自然语言仍可触发 Python 的场景只有：需要理解新目标、补齐创作要求、生成内容或解释结果。
如果无法唯一确定业务对象，Java 必须拒绝副作用，页面应引导用户回到对应卡片，而不是让模型猜测。

## 排障顺序

1. 确定性动作先查 Java access log、业务事件和 trace；此路径不应出现 Python span。
2. 免费问答或方案生成先查 Java conversation run，再按同一 trace 查看 Python Agent。
3. 已确认付费任务先查 Java task/outbox，再查 Rabbit 与对应 Python Worker，最后查供应商。
4. 投稿先查 Java media order 和旧 GEO 转发/回调；Python 不参与订单状态判定。

每个跨服务请求必须携带同一 trace 上下文；日志只记录脱敏引用、阶段和错误码，不记录令牌、签名、
完整提示词或供应商密钥。
