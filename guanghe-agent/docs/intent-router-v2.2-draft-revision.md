# Intent Router V2.2 与草稿修订

代码 Bundle `agent-bundle-v1.22.0`；不代表已部署或已通过真实模型验收。

复用现有 Router/Gateway、授权 Run Context、领域图与 `task-draft.proposed`，没有新模型调用或新数据库读写。

## 输入与判定

- 有界输入增加授权空间名（100字）、商品名（150字）、本轮附件数量和候选素材模式。名称只用于语义判断与面向用户的澄清，不进日志。
- 输出保留原八字段，增加必填 nullable `clarification`、`draftOperation`。
- CLARIFY 必须给 reason：`MISSING_CONTEXT`、`OBJECT_MISMATCH` 或 `AMBIGUOUS_REQUEST`；target 只允许已有 Context 路径。前两类由运行时生成提示，最后一类有一条最多300字的具体 question。
- 已选择对象不等于对象正确。OBJECT_MISMATCH 不因 ID 非空被删除；用户明确改回当前选项时重新判断，不固化历史冲突。
- PREPARE 必须明确 NEW 或 REVISE；其他决策该字段为 null。模型不输出草稿 ID。

## 提议与权限

- SSE `draftOperation` 缺省 NEW。NEW 的 `previousDraftId` 缺省或 null，非空禁止；REVISE 必须为非空 ID。
- REVISE 的 ID 只从 Java 本轮冻结 `taskWorkspace.activeDraftId` 取得。无候选、类型不符或来源不可用只澄清，不回退 NEW。
- Java 再检查冻结候选、所有者、会话、对象、来源、有效期与待确认状态并锁定原稿。Python 不作废任何草稿、不确认、不扣分、不执行正式任务。
- REVISE 参数基线只合并领域白名单，保留用户未改的数量、尺寸等；新字段仍走原领域校验。NEW 不自动继承旧稿参数，输入去掉旧 workspace。仅提供有限非身份呈现选项供本轮明确要求复用；不包含旧场景、商品事实、素材 ID。

## 当前附件边界

前轮 CURRENT_ATTACHMENTS 没有本轮重新授权附件时，明确要求重新附上原图，不降级为右侧商品。
重附相同已授权 assetIds 且明确修改当前草稿可 REVISE；不同 assetIds 应 NEW。Router 只接收是否匹配的布尔值，不得到素材 ID。
本批不实现无重传的跨轮附件授权，不宣称已支持此能力。Java 仍最终检查同来源并锁定。

## 观测与验证

`router_action_resolved` 仅输出受控 draftOperation、clarificationReason/Target、候选存在及来自授权上下文的布尔值；不记录问题正文、商品名称或 ID。定向覆盖对象冲突/恢复/取消、NEW 与 REVISE、伪造 ID 拒绝、附件补交及 IMAGE/VIDEO/ARTICLE 参数保留；真实模型效果需部署后另验收。
