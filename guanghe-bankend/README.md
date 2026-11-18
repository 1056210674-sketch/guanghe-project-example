# 光禾AI智能体框架 Java 后端

光禾AI智能体框架第一版的业务控制面和 PostgreSQL 唯一业务写入方。浏览器只调用本服务；目标架构中 Python 是受 HMAC 保护的智能执行服务，不直接连接 PostgreSQL，也不决定积分和任务终态。

## 资料更新后的短回复承接（2026-10-23）

复用既有 `AgentMemoryService` 与逐 Run 记忆边界。追加经营品类保留原有历史边界；
用户在聊天中更正资料时，从该条 USER 的创建时刻开始保留历史，包含同轮助手的补问，
避免下一句“其中一项”或“ok”丢失指代对象。删除记忆、页面编辑和既有边界继续阻止旧资料回流，
当前权威记忆仍优先于聊天。没有新增 API、Prompt、迁移、付费准入或自动执行权限。
修复用于后续写入，不回填既有历史截止线，也不改写历史 Run 快照。

## 创作连续修改的来源绑定（2026-10-23）

创作智能体复用原生图片/视频工具和付费准入。Java 通过 `context.read.creativeContinuation`
提供最近六条完整 USER/ASSISTANT 消息，历史原文合计不超过 16000 字符，不截断单条消息。
候选不是授权；Python Harness 需要结合当前用户要求做语义审查，Java 在准入时重建并核对指纹。
用户、会话、冻结项目（含未绑定项目）、当前原文和最新记忆边界都参与绑定，窗口内不完整证据不准入。

“把16:9改成正方形，内容不变”等省略对象的图片修改，只能绑定同会话最近完成图片任务的唯一
READY 产物，且原任务的 USER 在完整历史窗口内。跨项目、多图、已删除和用户发问后才就绪的
产物不能自动选取，也不会回退到更旧任务。Harness 只能转交 Java 选定的来源凭据；Java 强制
`PREVIOUS_IMAGE_RESULT` 并直接冻结该图片，不能通过 `TEXT_ONLY`、商品或附件模式替代。
当前用户原话仍是冻结 `requestText` 的唯一来源。既有预算、钱包、幂等和投递检查保持生效，
没有新增数据库迁移；Java 不直接调用模型供应商。

跨服务职责与允许调用清单见 [运行时边界](docs/runtime-boundaries.md)。

## 架构与新增智能体（2026-10-11）

当前是 USER/ADMIN 两角色的多用户系统，不是组织成员模型。接手开发请先读
[当前架构与数据边界](docs/architecture/current-architecture.md) 和
[新增智能体接入指南](docs/architecture/adding-an-agent.md)。运行权限清单镜像位于
`src/main/resources/contracts/agents/runtime-catalog.json`，源在 Python `app/agents`；
新 Run 冻结执行发布与清单哈希，工具按当前授权与冻结权限的交集执行。
发布前运行 `scripts/run-cross-repo-stability-gate.ps1 -RequireCleanWorktree`，不得将脏源码或
局部 JAR class 覆盖称为可复现发布。本轮没有新增组织表、删除客户数据或修改已应用迁移。

本地构建、功能检查与发布状态见 [基础改造记录](docs/architecture/architecture-foundation-acceptance-2026-10-11.md)
和 [当前架构清理记录](docs/architecture/architecture-cleanup-acceptance-2026-10-11.md)。用户自建智能体明确延期，
当前只服务开发者扩展内置智能体，不预建 Builder、私有配置表或新的执行框架。

## 文章执行版本兼容修正（2026-10-10）

文章回调沿用严格的已发布 Prompt Bundle 白名单，补充当前实际运行的 `agent-bundle-v1.46.3`，
避免合法的标题、结构和正文结果在版本校验时被拒绝。未知版本仍拒绝，不放宽正文 Schema、
任务归属、积分结算或投稿确认，也不增加数据库迁移。

文章质量规则升级配套精确增加 `agent-bundle-v1.48.0`；仅允许新版本生成结果通过既有校验，
不改变标题采纳、写作来源、任务准入或投稿流程。后续事实与建议分离规则修正配套精确增加
`agent-bundle-v1.48.1`；独立文章任务身份与统一成稿规则升级精确增加 `agent-bundle-v1.48.2`。
文章输出一致性修正精确增加 `agent-bundle-v1.48.3`；未发布的 `1.48.4` 等版本仍拒绝，历史任务版本与结果不改写。

2026-10-12 成稿修正精确增加 `agent-bundle-v1.49.0`。复用原完整文章管线，单篇写稿提议后的“继续做”可通过
同项目、紧邻、一次消费的服务端承接证明；一般讨论、混合监测/投稿仍不准入。冻结资料补充同会话明确全量确认的
来源事实，失败的后续模型回复不抹掉用户确认；更正/删除截止线优先，未把历史助手建议整体升级为事实或写入记忆。
正文用现有 CommonMark 的 GFM tables 扩展渲染对比表，继续使用原 HTML 清洗器，不放开任意 HTML/图片/链接。
无数据库迁移；旧文章和投稿快照不改写。

真实写稿复核后精确增加 `agent-bundle-v1.50.0`：围绕读者问题对齐题文，控制段落预算与品牌信息分布，
不把用户确认升级为公开核验。仅同步已发布文章版本和执行清单，不增加调用、计费或投稿权限。

文章编辑纠错规则精确增加 `agent-bundle-v1.51.0`：冻结提纲前先校正助手草案的错误前提，
不因品牌提供多项业务就把它们写成必须捆绑的行业标准；相关事实不自动等于达标或效果证据。
继续沿用原阶段与额度，不新增评分模型，不改历史文章或发布权限。

显式文章格式约束配套增加 `agent-bundle-v1.52.0`：Python 在原大纲/正文结构校验中检查
本轮无歧义的节数与字数区间，复用已有的一次有界修正，不新增任务、阶段或额外积分结算。
Java 仅同步版本白名单与来源清单，数据库、投稿确认和历史文章保持不变。

固定写作流程的文体与编辑规则配套增加 `agent-bundle-v1.53.0`：仍按 TITLE→OUTLINE→BODY 连续执行，
各阶段使用同一冻结需求与真实品牌资料，区分文体和语气，不把科普、产品介绍等全部写成采购指南。
用户明确的标题和章节职责保留，助手提议仅为可重新编辑的参考。标题候选的 rationale 不跨阶段持久化，
大纲从完整需求和已选标题组织正文，并通过现有创作方案把内容职责传给 BODY。
不新增任务、评分模型、业务表或调用阶段，不放宽事实、格式、积分及投稿确认边界。

2026-10-13 配套精确增加 `agent-bundle-v1.54.0` 并完整同步 Python clean commit
`5e0d4b1a3b56984d1b232adf8673685ebe7f52df` 的 OpenAPI 与运行清单。
写稿承接移除 ASSISTANT 提议的正则句式授权，不再因“一版／一整版”等同义表达
隐藏写作能力。Java 只提供同账号、同项目、紧邻、24 小时内、未消费且不越过记忆更正边界的
语义候选；Python 复用原有一次 article_action_review，只有 NEW_DRAFT 才由 Harness 绑定
当前／原 USER／ASSISTANT 三个消息 ID。Java 在原账号锁下重建候选、比对凭据，再沿原预算、
幂等和 TITLE→OUTLINE→BODY 管线受理。候选不是授权，模型不能自己构造凭据或选择历史消息。
三个阶段只是写稿工具内部的实现，不是每次会话都要经过的模板：资料足够且用户已要成稿时可直接
执行；用户想先讨论选题或改方向时自由讨论，不要求逐阶段向用户确认。
普通明确写作要求仍使用当前 USER 原文，不能被旧提议替换；完整短否定保持拒绝，但不再按
任意位置“不要写”等词片段误拒“不要写报价，按刚才主题生成完整新稿”。
只读审稿、仅改一段、方案讨论和投稿由 Python 原语义审查区分，不新建第二套路由或工作流。
本次扩展 Java Internal 的可选 Harness 字段，不改变 Browser/Worker 契约，不新增表或迁移，
不修改历史文章、记忆、积分和媒体发布流程。

## 客户项目阶段报告

### GEO 诊断与私有 PDF 交付

加号菜单通过 `/api/v1/geo/diagnostics` 进入六张持久业务卡片：品牌名称、资料核对、同类品牌、
自然发现问题、点名口碑问题、平台及报价。所有接口验证当前 USER、GEO 会话及绑定项目；
`requestId` 防止创建/生成重放，编辑使用乐观版本，上游变化使下游输入及旧报价失效。
卡片是 Java 保存的 `COMPLETED` 消息，`agentRunId=null`，不创建普通对话 Run。刷新读取会话
快照及诊断记录恢复卡片；不伪造免费回复的 SSE 生命周期。

V061 新增准备、卡片快照及生成记录三张窄表。显式生成使用既有签名 Python Client 的
`/internal/v1/geo/diagnostic-preparations`；单执行槽、60 秒模型期限和 90 秒数据库租约
隔离重启与迟到响应，每次按钮动作最多一个请求，失效后保留输入并等待用户显式重试。
每用户最多一个活动生成、每份诊断最多 20 次生成。重新生成保留用户添加或编辑的条目；
所有同类品牌最多五个，两组问题合计不超过十个。自然发现问题不得点名冻结的自家/竞品，
口碑问题须点名自家名称或品牌词，两类不得混入同一个自然可见度分母。

当前开关 `GEO_DIAGNOSTIC_ENABLED` 默认关闭，入口与后台准备 worker 均受保护；
轮询由 `GEO_DIAGNOSTIC_PREPARATION_POLL_DELAY_MS` 配置，默认 1000 毫秒。
本里程碑的报价复用现有 GEO 单元积分，诊断专用 `GEO_DIAGNOSTIC_MAX_PROVIDER_CALLS` 默认 200，
允许 1～1000；预算推导仍包含现有各平台采集路径和最大尝试数，不更改普通 GEO 调用上限。
正式提交另受默认关闭的 `GEO_DIAGNOSTIC_SUBMISSION_ENABLED` 保护。准备卡片本身不冻结积分；
用户确认有效报价后才提交一份 `GEO / DIAGNOSTIC_RUN` 后台采样任务，不占用普通聊天 Run。
默认八个问题、七个平台为 56 个样本；原账户单次 50、每日 200 积分限制继续有效。超过限额时
显示实际阻断原因，用户可减少平台或自行修改额度，不自动减配或提高额度。
公开检索来源保留实际 HTTP/HTTPS 链接，候选官网使用 HTTPS；这里保存资料而不抓取任意用户 URL。
模型使用量和搜索调用数分别保存，不能用搜索遥测创造新的收费口径。

V062 追加诊断交付及恢复请求表，历史迁移不变。自然发现和点名口碑分开统计，失败/缺失样本
不当作未提及；诊断专题和运行不进入普通监测列表、趋势或阶段成果分母。一次采样按有效单元
结算一次，随后独立分析与 PDF 租约执行；采样完成时不提前发“报告完成”通知。分析最多两次、
PDF 最多三次，显式恢复复用冻结证据，不重新采样、报价、冻结或扣费。耗尽后业务任务明确失败，
原始回答与结算记录保留。无有效样本时生成明确的失败说明 PDF，不冒充可见度为零或优化成果。

最终报告含两组平台指标、竞品对照、实际证据、优先建议与内容选题蓝图；中文 PDF 图文排版及
原文附录读取全部已保存回答和引用。模型输入使用有界摘录与来源投影，字段截断和排除来源有
明确限制说明，不覆盖原始证据。分析 V2 另以全文判定的私有 `matchedBrandIds` 保留自家及
选定竞品命中，短摘录中未出现名称不能推断全文未提及；咨询版通过 Browser DTO 投影该字段和冻结竞品资料。
旧 V1 冻结证据缺少该字段时仍原样恢复，缺少标记表示未知，不补零、不改证据哈希。
准备阶段提示明确标记阶段范围，不能误当最终报告的证据缺口。PDF 受 10 MiB/240 页安全上限保护，超限明确交付失败，在线
原文分页仍可读取。`reportVersion=1` 不随恢复次数变化，交付进度修订号与准备版本另行递增。
PDF 只存入私有 OSS，下载验证 USER、当前项目和精确报告版本后签发短期 HTTPS URL，响应
`no-store`；链接不能进入 Agent 上下文。`GEO_DIAGNOSTIC_REPORT` 为当前项目的只读资源入口，
允许 GEO 智能体据已交付诊断继续优化，不混用普通 GEO reportId。

诊断解读新回执采用 `GEO_DIAGNOSTIC_ANALYSIS_V11`，兼容发布中的 V10；历史冻结结果保持原样。结论逐条归属到具体回答，建议不混用自然发现和口碑组的细节。
统计摘要由 Java 根据本次冻结指标生成，替换模型回执中的摘要；按品牌标识汇总有效发现样本，口碑识别单列，无有效样本明确未知。咨询核心判断单独保存为可选 `strategy.coreJudgment`，附带认知观察、问题意图分层和建议路线图；行动项明确现状、影响、交付物与验收方式。问题 ID、分类、路线图动作索引和逐字引证均校验冻结证据。新分析读取最多 1600 字的连续原文窗口，优先保留品牌上下文，不拼接引文；完整附录保持不变。历史缺少 strategy 或命中字段正常降级，未知不补零。模型仍负责证据解读、建议和选题，不参与重算提及次数或分母；原模型回执校验与引用核对仍执行，不修改历史已交付结果。
报告说明区分解读摘录裁剪与完整原文保存，不能把摘录边界写成采样失败。PDF 附录复用现有 CommonMark 和
PDFBox 处理标题、列表、表格及 HTML 的可见文字，保留链接、所有正文与来源；表格以带列名的
行记录排版以适应长内容。标题与后续正文保持同页，长来源摘录可分页，中文闭标点避免单独换行。
字体不支持的符号显示文字标签，文末列出对应码点；方法与局限按首次出现顺序去重，仅合并完全相同的说明，不合并不同口径。在线原始证据不修改，既有 PDF 不重写。

交付 worker 的轮询配置为 `GEO_DIAGNOSTIC_DELIVERY_POLL_DELAY_MS`（默认 1000 毫秒），依赖
既有 GEO 执行、签名 Python Client 和私有对象存储。独立数据库门禁使用
`scripts/run-isolated-database-gate.ps1 -GeoDiagnosticDeliveryOnly`，仅限隔离 Gate 库，覆盖
061→062、运行角色默认 ACL、真实准入/结算、幂等恢复、迟到回执、失败说明与耗尽收尾。

`POST /api/v1/geo/period-reports` 是加号菜单的直接业务入口，不经 Agent 意图路由。输入
`requestId(UUID)、projectId、startDate、endDate、title?、focus?`；`projectId` 映射当前账号的
客户经营空间，个人创作存储不能作为客户项目。日期为北京时间自然日，含首尾且最多 92 天，不能含未来日期。
同账号相同 requestId 返回既有报告，改变输入返回冲突；一次重复读快照事务冻结统计、来源 ID、
文章版本和监测可比性结果。GET、PDF 和显式总结重试均不重新查询原业务数据。

V060 追加 `geo_period_report` 保存不可变事实、摘要状态和执行租约，沿用 Flyway/default ACL，
发布门禁必须检查运行时 SELECT/INSERT/UPDATE/DELETE 权限。单执行槽通过既有签名 Python Client
请求定性总结，不创建聊天、监测、投稿或积分任务。状态为 QUEUED/RUNNING/READY/SUMMARY_UNAVAILABLE；
每份报告最多两次执行，租约失效转为总结不可用，只有显式 `POST /{id}/retry-summary` 可再次执行。
空报告直接 READY 且不调用模型；模型失败仍可查看固定统计并导出 PDF。

已结束监测按 finished_at 归入周期，基线要求在周期开始前已完成；无周期前基线时明确使用周期内
首个有效批次。按专题/平台复用 GeoTrendService 的目标、地区、问题版本、采样轮次、模型和采集方式
检查，只对共同有效样本计算百分点变化，不合并不同专题或推断发文造成提升。失败、缺失不补为未提及。
新建文章包含草稿；媒体发布次数和已发布不同文章数分别统计，按系统首次确认发布时间而非媒体原始
时间入账；待处理/失败投稿按周期内新建且快照时状态单列。报告不包含费用、内部成本或利润。
明细最多六个专题、每专题一百批次、一百篇文章/投稿；汇总独立完整计算，截断明确披露，截断周期不算首尾提升。

`GET /api/v1/geo/period-reports/{id}/export?version=预览版本` 使用相同冻结数据离线绘制图表及 PDF，
不抓取任意 URL、不调用生图。已保存报告可通过项目列表重开；版本不一致拒绝导出，避免混用重试后的总结。

## 原生 GEO 对话工具（重构里程碑 1）

普通 GEO 对话通过受签名保护的 `POST /internal/v1/runs/{runId}/actions` 调用 `context.read`、`brand.save`、`geo.monitor`、`article.generate` 和 `memory.read/upsert/forget`。Java 从活动 Run 与 context ref 恢复账号、智能体、项目和原始 USER 消息；工具参数不能指定用户、项目、价格、权限或发布授权。没有绑定项目时仅返回可选项目，不猜测客户；`brand.save` 根据用户明确给出的品牌名称绑定或创建项目，只更新公开名称与别名，其他事实用结构化记忆保存，不再追加原始消息知识文档。

`geo.monitor` 支持无预建专题的 3–10 个问题，复用既有 discovery 物化、正式任务、积分冻结、队列和结果结算。同一 Run 只受理一种监测输入，重复工具调用及会话内相同运行中任务复用原任务。已配置的单次/每日预算优先；未配置时使用有限默认 `native-action-max-points-per-action=50` 和 `native-action-max-points-per-day=200`，可由 `zhixiaohe.agent-platform` 配置调整。正式任务记录 `AGENT_GEO_ACTION` 来源，不伪造确认令牌或用户点击。只有既有显式 `executionIntent` / `ARTICLE_FULL_V1` 继续走旧路径；此入口不能投稿。

V052 仅追加工具调用回执。普通原生 GEO Run 预算为 6 次模型、12 次工具、24 步，默认对话截止时间 3 分钟；已显式配置环境值优先。后台 GEO 任务沿用自己的租约与重试，免费对话停止不取消已准入任务。本里程碑的通过标准是新用户真实多轮对话及后台监测完成后的报告追问，不以编译成功替代功能验收。

`context.read.budget.configured` 只表示账号是否自定义额度，不能解释为能否执行。新增 `policySource=DEFAULT/ACCOUNT`
和 `status=ENABLED/DISABLED/NOT_EFFECTIVE/INVALID` 明确区分有效默认额度、用户禁用、未到生效日及无效配置；
生效日按现有 billingClock 的上海自然日判断，不另造时钟或修改额度。`message` 说明未自定义时无需先配置，
`finalAdmissionRequired=true` 表示本投影不承诺余额、今日剩余额度或报价通过；最终仍由原 Java 准入事务核验，
不能根据额度上限或空任务列表声称监测已提交/被预算拒绝。无数据库迁移，旧字段语义兼容。

`context.read.monitorContinuation` 只投影同项目、紧邻原 USER 的未提交证据：上一轮须在当前请求前
24 小时内正常结束，无任务草稿/任务动作，且全部工具计数有 Java 动作或只读资源回执可核对。
Python 再核对当前短句“提交”和上一条明确监测请求才开放工具；已受理、结果未知、跨项目或更早
历史均不续提。此投影不直接创建任务，也不替代既有准入与幂等。

`context.read.articleContinuation` 复用现有会话消息和完整文章准备记录：当前 USER 必须是严格的短肯定，
紧邻上一条 ASSISTANT 必须在同项目、24 小时内正常完成。Java 不识别助手的措辞来断言可否写稿，
而是返回 authorized=false、reviewRequired=true 的候选，由既有语义审查结合完整源文判断本轮
是否明确要生成新稿；教程、引用、代码、否定、仅认可方案和多动作不是自动执行授权。项目身份可代替旧 selectedSpaceId，
但冻结安装身份、账号、原 USER、提案 ASSISTANT 与当前 USER 都须一致，不可由模型传历史 ID。
上一轮无任务草稿、写作准备记录或付费动作；原生写动作均在 Java 账号/Run 锁事务内准入，正常结束
的旧 Run 不接受迟到操作，因此只读搜索不要求伪造 Java 工具回执。旧 monitorContinuation 规则不变。

投影包含 authorized/reviewRequired/action、currentMessageId/conversationId/projectId/sourceMessageId/offerMessageId、
requestText/sourceText/offerText（各上限 4000，超限不截断）及可选显式 geoReportId。
article.generate 的内部可选 continuationReview={decision:NEW_DRAFT,currentMessageId,sourceMessageId,offerMessageId}
只能由 Harness 在原有语义审查通过后注入，LLM 工具 Schema 不开放此字段。准入在既有账号锁下重新重建证据，
匹配三 ID 后才将 authorized=true、reviewRequired=false 与审查凭据冻入原有准备记录。
缺少、过期、跨范围或不匹配的凭据在创建授权和冻结积分前拒绝；普通完整需求不附这个凭据。
保存原始需求及已认可的标题/结构方向；助手提议不是新的品牌事实。原报告只从源 Run 的显式冻结
选择继承，当前另选报告或指定回复其他消息时不承接。来源记录冻结于文章 brief 的
authorizedContext.articleContinuation；父记录 editorial.requestText 保留当前 USER 确认原文，继续满足数据库
来源等值约束。标题、结构、正文阶段只将同一冻结 continuation.requestText 映射为现有 parameters.brief，
不回读实时聊天、不把承接来源字段传入模型执行契约。同 offer 已存在任何状态的准备记录均复用该结果，不重新扣费，
context.read 返回 authorized=false、reviewRequired=false、ALREADY_STARTED 及已有 preparation 引用。无需数据库迁移；
仍复用原标题→结构→正文管线与最后的投稿确认，Prompt Bundle 白名单精确补充 1.46.4。

短确认承接的写作输入按来源分区：本轮 USER 确认和原 USER 的完整编辑要求保留在正文任务 brief 中，
包括用户明确的题目、字数、受众及中立写作限制；ASSISTANT 单篇提议进入既有 `【写作参考／非授权】`
区域，仅保留主题和结构方向，不把助手的标题措辞、内部编辑说明或风险备注变为必须公开复述的命令。
追加历史仍处于同一参考区域。旧冻结输入保持兼容，不改准入、积分、幂等、事实来源或 Python 执行契约。

报告资源读取允许同一活动 Run 内的多份搜索候选集独立使用，后一次搜索不会使前一次失效。每次仍校验账号、会话、选定范围、上下文重置边界、有效期及整份候选集未被消费；跨 Run 沿用仅当前候选集有效的规则。首次成功读取后用返回的 `observationId` 继续读报告或证据，不能换一个工具调用编号再次消费同一候选集；相同 `toolCallId` 的合法重试仍返回原结果。资源读取、原生动作与进度事件统一先锁 Run，再访问会话序号；付费动作仍先锁积分账户再写会话。

聊天可通过原有消息字段 `geoReportId` 引用某一份已保存报告，新会话和同项目已有会话均可使用。选择阶段只查询列表和详情，不创建会话、Run 或监测任务。报告列表支持 `GET /api/v1/geo/reports?spaceId=项目编号&archived=false`，在服务端按项目过滤后分页，并返回冻结的 `topicName`、`platformCodes` 与原有 `generatedAt`。发送时拒绝跨账号、跨选定项目、与会话固定项目冲突、已归档项目/报告及非终态报告；显式报告投影失败也拒绝本次提交，不降级到其他报告。显式报告优先于以前读过的报告；本轮资源工具即使使用 `LATEST_GEO_REPORT` 或 `LATEST_USABLE` 也只能读取所选报告，其回答原文必须来自同一报告，不偷偷替换成后来最新监测。新建固定项目会话首条消息使用 `INHERIT` 时，会继承已经授权的身份项目，无须用户重新选择同一项目；显式 `REPLACE/CLEAR` 语义不变。

## 文章画布与资产目录

V055 将实时预览与正式正文分开。Python BODY 生成时通过原签名 `execution-events` 上报 `TASK_PROGRESS / ARTICLE_BODY_STREAMING`，`details.articlePreview={attempt,sequence,markdown}` 是真实流式累计快照（不超过 120000 字符），不是前端模拟打字。Java 在任务锁后复验最新执行 token、租约、BODY 所属准备记录及账号/项目；同一执行的序号单调递增，修复轮先发送空快照。只保留最新独立预览，任务事件只留通知引用，不反复存完整正文。浏览器用 `task.progress.articlePreview` 通知和 `GET /api/v1/article-full-preparations/{id}/preview` 恢复画布，失败保留明确标识，只有准备记录成功及正式 Article 完成才开放成稿操作。

文章完成后可直接走现有投稿确认，或 `PUT /api/v1/articles/{id}/asset-collection {version}` 保存到资产中心。目录标记绑定同一 article 和保存时的版本，不复制正文、不递增正文版本、不收费；重复保存同版本幂等。`GET /api/v1/articles?savedToAssets=true` 返回已保存且完成的文章，后续继续使用同一 Article 编辑接口，编辑标题/正文会撤销原投稿就绪态并递增版本，旧投稿快照不变。图片仍通过现有受控素材关联保存；本轮没有放开含图媒体发布。

生成块中的 Markdown 使用官方 [CommonMark Java 0.27.1](https://github.com/commonmark/commonmark-java/tree/commonmark-parent-0.27.1) 渲染段落、列表及强调；启用 `escapeHtml/sanitizeUrls` 后再走既有 Jsoup 白名单。最终正文展示使用 `bodyHtml`，不能把清洗器扁平化的 `bodyText` 当成 Markdown。

部署时会话 `/api/v1/conversations/{id}/events` 必须命中关闭缓冲的 SSE location。通用 `/api/` location 不得使用 `^~`，否则 Nginx 跳过 SSE 正则 location、把真实增量积攒后一次返回。模板包含专用 SSE 配置及安全响应头；端到端验收应观察成稿前多个递增正文快照，HTTP 200 或最终正文完整不能代替流式验证。

## 智能体平台基础

`GET /api/v1/agents` 返回平台维护的已发布目录；`/api/v1/agent-installations` 只按 `agentKey` 添加、版本化启停或移除自己的添加记录，不接受脚本、任意配置或外部端点。当前服务端只准入 `geo/1.0.0` 和内部验收用的 `summary/1.0.0`，且定义必须匹配已注册适配器。内部助手需要同时设置 `ZHIXIAOHE_AGENT_PLATFORM_INTERNAL_AGENTS_ENABLED=true` 和私有服务器环境中的 `ZHIXIAOHE_AGENT_PLATFORM_INTERNAL_AGENT_USER_IDS`（逗号分隔测试账号 ID）；默认空名单对所有用户隐藏并禁止新运行。生产平台开关 `zhixiaohe.agent-platform.enabled` 默认开启，`mock` profile 默认关闭。非 `mock` 环境关闭或配置非 `true` 值会在普通业务组件初始化前以 `AGENT_PLATFORM_ENFORCEMENT_REQUIRED` 拒绝启动；撤销访问应禁用定义或安装，不能关闭身份校验回退旧 GEO。

`GET/POST /api/v1/projects` 复用 `business_space` 的 ID、归属和关联，创建只要求名称，不虚构品牌或商品。旧 `/spaces` API 保持品牌必填契约，仅列出有品牌的空间；项目仍可由归属校验用于授权资料上下文。项目状态为 `ACTIVE/ARCHIVED`，归档项目不能开始新 Run。

V047 新增目录版本、用户添加记录及独立会话身份表。定义内容和会话身份由数据库保护不可改；状态禁用、添加记录停用或软删除均拒绝新 Run，但历史身份可读。旧会话纯读取只返回无添加记录、无项目的 GEO 兼容身份；首次发送才绑定默认添加记录，绝不从当前临时空间推测项目。此阶段不放宽既有付费提案、扣费、确认或发布边界。隔离迁移验证入口为 `scripts/run-isolated-database-gate.ps1 -AgentPlatformOnly`，不得对共享数据库运行该 Gate。

## 项目知识、长期记忆与完整文章

品牌档案渐进补充直接复用公开名称 `/projects/{id}/geo-target` 和 PROJECT 结构化记忆，不新增表或迁移。
`memory.upsert` 的可选 `mode` 默认 REPLACE；APPEND 仅允许已有且未删除的 PROJECT FACT `brand.categories`。
新增片段仍必须逐字来自本轮 USER，Java 通过当前版本和来源校验后保留原值、以换行追加并仅去重完全相同的片段，
合并结果仍最多 2000 字符。服务范围 `brand.service_area` 与推广范围 `geo.target_regions` 不允许 APPEND，
临时推广地域不得覆盖实际服务地域。首次提供或墓碑恢复使用 REPLACE，不从旧历史补回内容。
浏览器编辑墓碑须显式 PUT `{version,label,value,restore:true}` 并提供新的非空内容；未删除记录不能使用 restore=true。
恢复保存为 USER_INPUT 并更新历史边界。未带 restore 的旧客户端仍不能恢复已删除内容；POST 同 key 继续冲突。
资料创建、补充和恢复均不创建监测/写稿任务，也不修改既有监测快照。

普通对话明确要求写文章时，原生 `article.generate` 接受可选 `tone/targetLength`，其余输入全部来自本轮原 USER 和服务端冻结项目/报告；用户显式选择的旧报告不会替换为最新报告。一般“提高可见度/帮我优化”仅给方案，不自行监测或写稿；只有明确监测才调用 `geo.monitor`。本次不新增监测完成后自动写文的工作流。

`context.read.latestArticle` 只读取当前账号、项目、聊天在本轮 USER 之前最近准备记录对应的实际文章，返回版本、文章/准备状态与最多 12000 字符正文，保留段落并明确是否完整或截断；不会用流式预览冒充成稿，也不接受模型传入文章 ID。最新任务失败时不静默改读另一篇旧稿。读稿、质疑稿件均是只读行为。
记忆更正/删除的 ACCOUNT＋PROJECT 历史边界继续生效：边界前的聊天和旧稿不进入自动写作参考。正式文章仍可核对，但 `bodyPredatesMemoryBoundary=true` 时只代表旧文档内容，不是当前品牌事实，不能重新沉淀为记忆。
正式文章准入后，Java 可将最近有限同聊天 USER/ASSISTANT 文字及旧稿节选另冻为 `articleWritingReference`；它只是写作参考，不是品牌事实、用户确认或执行权限。本轮原 USER 不变且优先，匿名/中立/不提品牌等要求不会被旧提议覆盖；只使用既有 4000 字符 worker brief 的余量，截断参考有标记。短确认先通过同范围候选的语义审查与 Java 复验，stage 只物化已冻结参考，不重读实时历史；审计来源在发送 Python 前剥离，不新增 Provider 字段。文章结果允许精确版本 `agent-bundle-v1.47.0`，未知版本仍拒绝。

V054 扩展工具回执 action 和文章授权来源，`AGENT_ARTICLE_ACTION` 绑定唯一原 Run，按现有单次/每日自动执行额度准入，默认仍为 50/200 分，校验余额并一次预留完整三阶段费用。它不是用户点击确认记录，不制造标题/提纲确认时间。后台直接复用 `ArticleFullService` 的 TITLE→OUTLINE→BODY、队列、租约、重试、结算和资料版本复验；每 Run 至多一篇，相同重试返回同一准备记录，变更参数拒绝重复扣费。工具 `COMPLETED` 表示已受理，只有准备记录 `SUCCEEDED` 才表示文章完成，失败不伪造正文。

受理事务将可信 `content.articlePreparation={preparationId,articleId}` 写入助手消息，后续文本、失败或停止不抹掉这个引用，`message.completed` 同步返回。浏览器刷新可恢复进度并访问现成文章编辑页。`PaidTask.articlePreparationId` 区分自动准备的中间任务，不能展示独立 TITLE/OUTLINE 的人工确认卡。生成不等于投稿：媒体推荐、内容/媒体/费用确认、投稿快照和确认令牌仍使用现有发布流程；本工具不能发布。

V053 增加一张 `agent_memory_entry` 当前值表。客户事实、项目表达偏好都默认 PROJECT；ACCOUNT 只保存用户明确要求所有项目通用的表达偏好，不能保存品牌经营事实。模型不能传项目或用户编号，服务器从固定会话身份或可信当前选定项目恢复范围。原 `以后/今后` 消息前缀不再自动写入 PERSONAL。

记忆按稳定 key、乐观 version 和本轮 USER 原话更新。更正替换当前值；删除清空 value 与原 label，留下无值墓碑，旧消息或旧版本不得恢复。`knowledgeContext.memory` 和 `memory.read` 返回当前项目及账号通用记忆的完整权威投影，包含墓碑及 revision；每范围最多 100 条、key/label/value 合计 6000 字符，达到限额明确拒绝，不静默截断墓碑。用户可在资料库通过 `/api/v1/memory/entries` 列表、创建、版本化编辑和删除，显示作用域与来源。

更正/删除生成当前 PROJECT 或 ACCOUNT 的历史边界。Java 组装和读取 Run 快照时裁掉边界前的模型历史与摘要，保留当前 USER、独立任务和报告引用；原聊天记录不物理删除。其他项目不受项目级更改影响。ARTICLE_FULL 在新阶段执行前同时比较记忆投影与文档版本，变更后拒绝继续使用旧授权资料。业务写稿/发表次数仍从文章和投稿记录计数，不由模型累计。

旧偏好及旧对话资料不做低置信自动迁移：旧 `/memory/preferences` 保留给用户查看、修改或删除，但不再自动注入模型；需要用户核对后显式转存进有作用域的新记忆。有 `brand.save` 回执明确关联的旧对话知识文档不再召回或通过冻结资料重验，手工上传文档不删除、不强迁移。本次不包括多账号共享、团队权限或全局删除聊天历史。

原生对话自动压缩通过 `POST /internal/v1/runs/{runId}/compaction/source` 和 `/checkpoint`，复用活动 Run、HMAC、项目归属与 `conversation.summary/summary_version`，不新增数据库结构。Java 按持久水位分页读取原始终态历史（而非已经被 token 预算裁掉的投影），保留当前 USER 和最新消息；Python 生成摘要，Java 以 expectedVersion、确切源 hash、记忆 revision、项目/遗忘边界复验后保存。account→Run→conversation 的锁顺序避免与记忆更正交错；原样重试幂等，旧摘要不能越过新遗忘边界。摘要仅为历史资料，不是业务事实或发布授权；任务、报告、待确认信息仍读取独立业务真相。每个成功 checkpoint 都可供后续 Run 继续，原聊天消息不删、不修改。

V049–V051 追加一次性文章授权/执行、项目知识与表达偏好、HOURLY 监测类型；不回填旧资料、不改写已保存报告或已授权计划。项目可改名、归档和恢复，旧空间、商品及历史任务继续复用现有归属关系。

项目名称只用于整理工作，不会自动当作公开品牌。`GET/PATCH /api/v1/projects/{id}/geo-target` 让用户显式设置公开监测名称与别名；版本沿用项目版本，归档项目不能修改。设置复用已有品牌名称和别名存储，不覆盖原经营资料。名称未设置或不可识别时，新 GEO 草稿、确认与计划报价在收费前拒绝，历史报告仍保留原冻结目标，不因后来修改公开名称而重算。

项目知识支持粘贴文本、TXT、Markdown 和文字型 PDF。上传限制 20 MiB，PDF 最多 100 页，不支持 OCR，不自动访问链接。每版保存散列、段落和页码；失败、打断或资料更正均可追溯。删除后不再进入后续上下文。长期记忆仅保存用户明确给出的偏好和真实写稿/发布记录，不推断性格；个人与项目偏好单独管理，执行时按授权项目合并并冻结。

原 USER 消息可显式设置 `articlePreparationMode=ARTICLE_FULL_V1`。一次有限积分上限授权后，系统在同一篇文章上连续执行标题、提纲和正文，不要求用户逐步确认。授权不能来自助手文本、旧会话或免费提案；各阶段复用现有报价、预留积分、任务、租约、结果和结算事务。资料版本变化在尚未出网的阶段以 `KNOWLEDGE_SOURCE_CHANGED` 终止，历史正文保持不变；已提交或未知执行结果不会因撤销授权误退款。撤销只阻止新的阶段，不取消已确认付费任务。

正文可编辑，媒体推荐只读取实际可投稿资源及价格，不生成投稿订单。发布仍必须展示当前正文版本、媒体和实际报价，由用户最后确认；刷新恢复不得重复收费或默认发布。GEO 报告导出为认证下载的 MD/PDF，直接使用已保存报告，不触发采样或模型。

从报告发起完整文章时，原 USER 的显式报告编号和服务器冻结的 `EXPLICIT_REPORT` 上下文必须与后续准备请求一致；不能漏传、替换或从“最新报告”补入。旧消息只有明确冻结的显式来源才兼容。文章执行器严格接收 Java 保存报告的结构化建议及其来源编号，不把来源编号交给模型当授权。

固定完整文章目前复用明确的公开监测对象；项目名称不能充当品牌。未配置或对象不可识别时，在授权/冻结积分之前提示设置公开名称，不创建付费文章。该配方不接受附件，冻结并向各阶段显式传递空的 `sourceAssets`；报告、知识和品牌仍使用原冻结版本，不在后续阶段偷偷替换资料。手工单阶段文章的原行为不变。

GEO 执行器读取既有签名 `geo-observations` 页时，同时取得 `visibilityEvidence`：它按本轮冻结名称/别名匹配已保存回答全文，包含同 Run 全部已保存样本（最多 210 条），并不随当前 `items` 分页或回答摘录而裁剪。首次解读与解读恢复复用同一匹配投影；失败、空回答及目标未知保持 null，不算未提及，也不读取后来改名的品牌。该读取不触发模型、采样或数据库写入。

管理端的智能体目录、监测计划、知识资料和记忆元数据接口使用既有管理员鉴权和审计；不展示知识正文、偏好内容、来源 URL 或用户私密资料名。HOURLY 只监测，不定时生文或发布；错过时段、上次仍运行和归属失效明确跳过，不自动补跑或扣费。

HOURLY 创建、修改和恢复共用可信上海日期下界计算，只登记未来槽；生效日期缺失时拒绝新增/恢复。调度入口也独立检查执行日和槽日期，历史槽不能提前收费。已有 DAILY/WEEKLY/INTERVAL_DAYS 语义不变。

隔离数据库入口 `scripts/run-isolated-database-gate.ps1 -ArticleFullOnly` 验证 048→051、完整三阶段结算、资料更正/删除、管理查询及调度边界。共享库部署必须另行核对制品与 Gate 证据，禁止将隔离 Gate 指向共享库。

## GEO 持续监测

`GET /api/v1/geo/topics/{topicId}/visibility-trend` 提供当前用户专题的 7/30/90 天只读趋势，按自然发现题或点名验证题分组。初始冻结问题与轮次作为持续跟踪基准，新增问题单独探索；相邻两批的共同有效问题版本和轮次用于环比，即使更早的问题 ID 曾改写也不会污染后续两批比较。固定基线折线仍只看最初题组，两种比率不混算；失败、缺失、历史采样方式未知不补零或强行连线。历史读取限制为 100 个展示批次、100 个基准扫描批次及 8 MiB 回答证据，达到上限在响应中标注。对话显式选定旧报告时，趋势只读取截至该报告所属 Run 的批次（同时间戳按 Run ID 截断），不会穿越到后来的采样。

V042 仅增加可空的 `geo_observation.collection_method`，执行器提供实际版本化采样路径；历史不回填推测值。独立验证使用 `scripts/run-isolated-database-gate.ps1 -GeoTrendOnly`，覆盖 V041→V042、历史证据不变、运行用户权限、约束及无积分变化；不创建真实监测任务。

## GEO 竞品发现与同批比较

AI 对话每轮使用可选 `geoInsightContext` 读取已保存的 GEO 事实，不新增工具端点、模型采样或业务写入。浏览器可逐条消息显式携带 `geoReportId`，它参与幂等指纹且必须属于当前用户及选定经营空间/商品，不能与当次图片附件混用。无显式报告时仅查询当前空间/商品范围内最多 6 个专题；恰好唯一专题才取最新未归档报告，并标记 `AUTO_SINGLE_TOPIC`；多专题只返回选择项，不从历史助手文本猜授权。

完成的 GEO 只读回答可带 Java 生成的 `content.geoContext`：仅冻结报告引用、文章准备资格及专题选择项，不附原始报告正文。Python 的可选 `run.completed.geoContextUsed` 只表示本轮使用了 GEO 上下文；所有引用均由 Java 从该 Run 已授权快照投影并保存到现有消息 JSON。普通聊天、停止/失败及旧快照不会凭历史文字补出动作；刷新也不重新查询最新报告替换原引用。文章准备资格只是当时状态，既有 article-workflows 端点仍重新校验，并不确认付费生成或投稿。

已有报告优先查看已保存结果；用户明确更新才准备新监测。无报告且已有专题时先配置现有专题监测，不自动重复创建同名专题；无专题时可准备 AI 拟题草稿。`QUESTION_DISCOVERY` 草稿可通过既有创建/修订端点修改问题与平台并重新报价，修订会使旧确认令牌失效；直到显式确认才创建正式专题与任务。新建自然语言 ARTICLE 提案如携带 `geoReportId`，必须与本轮 `READY` 且 `EXPLICIT_REPORT` 快照中的报告及选定空间/商品完全一致；普通文章不会自动绑定侧栏报告。未显式选定报告时，用户说“根据这份报告写文章”应使用原回答的冻结报告按钮，不以新一轮自动查询到的最新报告替换。修订已有文章草稿时则保留被锁定旧稿的报告绑定，不受后来最新报告改变影响，也不能改绑。标题、方案、正文和媒体投稿继续使用独立的明确确认，不承诺发布后可见度上升。

报告转文章保留用户事务锁、幂等键和同报告/建议的工作流身份防重；同一身份已在其他会话时引导回原会话，不移动或复制文章。不同报告可以在不同会话准备新稿。目标会话只有仍有效的文章待确认稿或实际 `QUEUED/RUNNING/RETRY_WAIT` 文章任务会阻止再开工作流；历史 `CONFIRMED` 本身不表示仍在运行，完成、失败或过期历史不会永久占用账号。单文章生成仍由文章行锁、版本和阶段校验保护，不改写历史记录、不自动扣分或投稿。

投影上限 16,384 字符，读取现有 DISCOVERY 同批可见度、30 天趋势最近比较及缓存竞品洞察。报告/竞品标记 `COMPETITIVE_DISCOVERY`（排除自家和已知竞品点名问题），趋势标记 `SELF_DISCOVERY_COMMON_BASELINE`（排除自家点名，再按共同样本比较），两区不得直接混算。全报告旧解读单独标记 `ALL_REPORT_QUESTIONS`，不能用其中样本总数代替自然发现分母。可选只读查询在独立事务执行，趋势失败仅降级该段并记录脱敏阶段诊断；权限错误仍拒绝。统计不因展示裁剪重新计算，缺少身份/有效回答保持 null，全失败趋势可没有报告 ID。聊天上下文不触发竞品分析、候选确认、监测重跑或积分变化，相关动作继续由工作台或草稿确认流程完成。原有上下文快照缺失该字段仍可读取，无数据库迁移。

新 GEO 报告在原有解读调用内提取最多 6 个待确认竞品，每个候选最多 2 条原回答证据。Java 对完整已保存回答重验名称、原句、问题版本和所有权，排除自家品牌/产品及别名、商城、店铺、媒体与来源-only 信号；候选不会自动写入正式竞品库。确认/关联复用 catalog，并可原子追加主题的新竞品集合版本；忽略保留 tombstone，不被下一批发现复活。现有报告、监测任务、积分与已授权自动计划均不被确认操作改写。

`GET /api/v1/geo/reports/{reportId}/competitive-insights?questionScope=DISCOVERY` 只读。可见度为同批完整成功回答的显式名称提及比例（0–100），不是推荐、排名或市场份额；失败和缺失不补零。问题直接点名本批冻结自家、正式竞品名称/别名或本报告发现对象时归入 `BRAND_VALIDATION`。忽略候选从比较表隐藏，但不会改变报告的冻结分类。发现使用裁剪证据时显式标注；计数始终基于完整保存回答。

竞品投影 `geo-competitive-insight-v2` 仅对已保存原句明确出现的严格中文品牌（单个 ASCII 英文词）并列名称作确定性归一：显示稳定中文名，中文或英文独立命中均计数，英文保留完整词边界。产品、容量/型号及 PRO/STEEL 等限定词不拆分；这不意味着已独立核实英文为正式别名。旧洞察也仅由自身冻结名称/证据投影，不回写历史；候选兼容旧组合名称 key 并保留 ID 与忽略状态，存在多条旧身份时不自动合并。确认新建采用中文主名、别名为空，不修改监测计划。

历史报告只在显式 `POST /api/v1/geo/reports/{reportId}/competitive-insights/analyze` 后分析；复用现有解读开关、Python 客户端和单执行槽，单用户与解读恢复共用互斥准入。每报告缓存首个成功结果，最多 2 次执行尝试、每次 60 秒/2 次 provider 调用；确认的零调用容量拒绝采用有界等待，不消耗实际执行尝试。借鉴建议引用具体原句，作为待验证清单，不推断竞品表现的因果。

V043 仅新增 `geo_competitor_candidate` 与 `geo_report_competitive_insight`；不回填历史，不修改 V042。隔离验证入口为 `scripts/run-isolated-database-gate.ps1 -GeoCompetitiveOnly`，包含真实 MyBatis nullable/UUID 读写、所有权、租约 fencing、重试上限、忽略保留与原报告/积分不变。

## 技术基线

- Java 21
- Spring Boot 3.5.16（Spring MVC 模块化单体）
- MyBatis-Plus 3.5.17（唯一业务数据访问层，不使用 JPA）
- Flyway 11.20.3
- PostgreSQL JDBC 42.7.13，目标数据库 PostgreSQL 18
- Redis、RabbitMQ、Quartz

Spring Boot 3.5.16 支持 Java 21；MyBatis-Plus 使用官方 Boot 3 starter。Flyway 11.20.3 和 PostgreSQL JDBC 42.7.13 显式覆盖 Boot 管理版本，以包含 PostgreSQL 18 支持及较新的驱动修复。

## 标准构建与本地验证

Windows 上只使用仓库的可信构建入口。它默认选择 `D:\Dev\Java\jdk21`，直接检查该目录中的
`java.exe` 和 `javac.exe`，确认 Maven 实际运行在同一个 JDK 21 上，然后执行 `clean verify`。
构建结束后脚本会重新汇总本次生成的 Surefire XML；Maven 即使返回 0，只要没有报告、报告无测试、
存在失败/错误或构建内 Git Commit 与仓库 HEAD 不一致，脚本仍判定失败：

```powershell
.\scripts\build.ps1
```

JDK 安装在其他位置时必须显式传入，不依赖系统中可能失效的 Oracle `javapath`：

```powershell
.\scripts\build.ps1 -JdkHome 'D:\path\to\jdk-21'
```

也可用 `ZHIXIAOHE_JDK_HOME` 设置团队机器的 JDK 21 位置。在受控测试选择中可以使用以下命令
强制要求 Surefire/Failsafe 汇总为 `skipped=0`；有任何条件测试未执行都会失败：

```powershell
.\scripts\build.ps1 -RequireZeroSkipped
```

入口始终在这些选项之前执行 `clean verify`，汇总 Surefire 和存在时的 Failsafe XML，并拒绝
`skipTests`、`skipITs`、`maven.test.skip` 等跳过参数以及 `test`、`groups`、`includes/excludes` 等
缩小测试集合的参数。`-RequireCleanWorktree` 用于最终 Gate/发布证据；
它会在 Maven 前后分别核对 HEAD 和工作树，并拒绝 `git.dirty=true` 的制品，避免只显示
HEAD Commit 却遗漏构建期间发生的提交或未提交代码。

正式 M0/M1 远程 Gate 必须在测试服务器的受控环境中运行 Linux 执行器：

```bash
bash scripts/run-m0-m1-gates.sh
```

该执行器将互相冲突的数据库初始化/运行态条件分阶段执行，并固定执行 13 个 Surefire suite、
1 个 Failsafe suite，共 39 个 testcase；只有 `failures=0 errors=0 skipped=0 flaky=0` 才通过。
`EXPECTED_AGENT_COMMIT` 必须由受控环境显式设置为本轮最终 Python Commit，没有默认值或回退值，
并且必须同时匹配 Python HEAD 与 Java 仓库的 `agent-openapi-source.json` 来源清单；执行器还会在
Gate 前后核对 Java、Python 两个仓库的 HEAD 不变且工作树干净。每次 Gate 还会在测试阶段前后
读取 `zhixiaohe-java-test.service`、8080 实际监听进程和无代理 HTTP/1.1 `/actuator/info` 响应，
要求运行 Commit 与 Java HEAD 精确一致，且 systemd MainPID 在整个 Gate 期间不变。
`AGENT_REDIS_FAILURE_BASE_URL`
必须指向与正式 Agent 不同的 loopback 隔离故障实例，禁止为测试停止共享 Redis。执行器拒绝任何
命令行参数及通过 Maven/JVM 环境继承的跳过、测试范围缩小或 `rerunFailingTestsCount` 参数；`.mvn`
配置和 POM 也不得包含重跑设置，JUnit XML 出现任何非零 flaky/flake/rerun 元数据均失败。
`GATE_EVIDENCE_ROOT` 必须显式指向仓库外已存在的持久化目录；执行器不会使用 `target/` 作为只读
证据的最终存放位置。

成功后，最终目录只保存移除了 properties、system-out、system-err、主机和路径信息的脱敏 JUnit XML、
脱敏的 `GATE-SUMMARY.txt`/`MANIFEST.txt` 和 `SHA256SUMS`；原始 Maven XML 只在仓库忽略的
`target/` 中用于当次 Gate 判定，不会复制进最终证据包。`TRACE-EVIDENCE.txt` 仅保存 trace/span 标识
和关联、脱敏检查结果；执行器会从固定 Java/Python systemd unit 复核同一 trace、跨服务 span 变化，
以及 Secret、签名和完整请求体均未进入相关日志。清单自校验通过后整个证据目录改为只读。
`M1-CONTRACT-EVIDENCE.txt` 只保留固定 11 个白名单证据项，并逐项严格校验 HTTP 状态、错误码、
Commit 及 trace/span 格式；不会复制其他 stdout。执行器不回显 Maven 原始输出或环境变量值。
`JAVA-RUNTIME-EVIDENCE.txt` 只保留 systemd 状态、loopback 监听结论、仓库/运行 Commit 绑定和
Gate 前后稳定性结论，不保存 PID 数值或原始 Actuator 响应。
Nginx、HTTPS 和整机重启恢复仍须作为独立部署证据验证。

无需外部基础设施的测试使用 `mock` profile，由上述标准构建自动执行。不要以裸跑
`.\mvnw.cmd test` 的退出码作为构建通过证据。

真实启动所需连接信息全部通过环境变量注入。开发环境模板见
`src/main/resources/application-dev.yaml.example`，本地覆盖模板见
`src/main/resources/application-local.yaml.example`。不要把本地或测试环境密钥写入仓库。

发票申请提交后写入持久飞书通知队列。只有配置服务器环境变量
`INVOICE_FEISHU_WEBHOOK_URL`（飞书群自定义机器人 HTTPS Webhook）后，后台才会尝试推送；
若群机器人开启签名校验，再配置 `INVOICE_FEISHU_WEBHOOK_SIGNING_SECRET`。
未配置时申请仍可提交，通知保持“未配置/待推送”，管理台可手动重试；历史申请不会自动补推。
飞书成功只表示开票信息已送到群机器人，不表示发票已开具或邮件已发出。
管理员须在线下开票并亲自发送至申请邮箱后，填写发票号、确认已人工发出；
系统记录确认操作人和时间，但不证明申请人实际收件。本服务不生成发票或自动发送发票邮件。

## 公网测试数据库开发模式

测试数据库使用公网直连，不使用 SSH 隧道。为减少测试阶段开发人员 IP 白名单和前置配置操作，
负责人已明确接受测试服务器将 PostgreSQL SSL 5432、Redis TLS 6380 和 RabbitMQ TLS 5671
开放至 IPv4 公网的风险。该决策只适用于测试服务器，不是生产网络基线。

PostgreSQL 公网规则只匹配 `zhixiaohe_test` 和日常运行账号 `zhixiaohe_test_app`，必须使用
`hostssl` 与 `scram-sha-256`。Flyway 高权限账号不长期向 `0.0.0.0/0` 开放，也不得通过扩大
数据库或账号匹配范围使其他现有数据库获得新的公网访问权限。

日常 Java 开发只使用 `zhixiaohe_test_app`。在 IntelliJ IDEA 的运行配置中一次性设置：

```text
SPRING_PROFILES_ACTIVE=dev
SPRING_FLYWAY_ENABLED=false
APP_DB_URL=jdbc:postgresql://<服务器公网 IP>:5432/zhixiaohe_test?sslmode=require
APP_DB_USERNAME=zhixiaohe_test_app
APP_DB_PASSWORD=<由负责人发放>
JWT_SECRET_BASE64=<至少 32 个随机字节的 Base64 编码，由负责人发放>
REDIS_HOST=<服务器公网 IP>
REDIS_PORT=6380
REDIS_USERNAME=zhixiaohe_test_app
REDIS_PASSWORD=<由负责人发放>
REDIS_SSL_ENABLED=true
REDIS_KEY_PREFIX=zhixiaohe:test:
RABBITMQ_HOST=<服务器公网 IP>
RABBITMQ_PORT=5671
RABBITMQ_VHOST=/zhixiaohe-test
RABBITMQ_USERNAME=zhixiaohe_test_app
RABBITMQ_PASSWORD=<由负责人发放>
RABBITMQ_SSL_ENABLED=true
INTERNAL_HMAC_ENABLED=true
INTERNAL_HMAC_KEY_ID=agent-service-v1
INTERNAL_HMAC_SECRET=<至少 32 个随机 UTF-8 字节，由负责人发放>
INTERNAL_HMAC_ALLOWED_CLOCK_SKEW=60s
INTERNAL_HMAC_NONCE_TTL=120s
AGENT_BASE_URL=http://127.0.0.1:8000
JAVA_M1_READY=true
JAVA_M1_SERVER_READY=false
M0_GATE=NOT_RUN
```

此模式不读取、也不要求 `FLYWAY_DB_*`，开发人员不需要登记公网 IP。`dev` profile 为其余非敏感启动项提供开发默认值，并在 Quartz 表迁移完成前默认关闭调度器启动；
这些值都可通过同名环境变量覆盖。配置一次后可直接正常启动 Java 服务，不需要每次执行 SSH 命令。
开发机不安装本地 PostgreSQL、Redis 或 RabbitMQ；Java 和后续 Python 联调进程都使用测试服务器中间件，
但 Python 仓库仍由 Python 项目独立维护，Java 仓库不修改它。

JWT 访问令牌固定有效期为 72 小时（259200 秒），不滑动续期。登录失败限制默认在
15 分钟窗口内按规范化账号和来源 IP 分别累计 5 次；Redis 不可用时登录按失败关闭，不会绕过限制继续签发令牌。
`dev` profile 的所有 Redis Key 都以 `zhixiaohe:test:` 开头。成功登录只清理该账号的失败计数，
来源 IP 计数保留到 TTL 到期，避免使用一个有效账号清空整台开发机的 IP 限流历史。

## 测试中间件与多人开发隔离

- Redis 使用光禾AI智能体框架独立实例和 ACL 业务账号，不复用服务器原有 6379 实例；业务账号只能访问
  `zhixiaohe:test:*`，不能执行 FLUSHALL、FLUSHDB、CONFIG、KEYS 等管理或危险操作。
- 服务器当前为 Redis 6.0 且 default ACL 用户保持关闭；Java/Lettuce 固定使用 RESP2，先通过独立用户
  完成 AUTH，再执行限流 Lua。RESP2 只是应用层协议兼容设置，不提供传输加密；公网 6380 必须同时
  启用 TLS 并校验仓库内的光禾AI智能体框架测试 CA，禁止 trust-all。不得为了 RESP3 握手而启用匿名 default 用户。
- RabbitMQ 只使用 `/zhixiaohe-test` VHost 和其独立账号，不复用 `/`、`/zhixiaohe` 或其他项目资源。
- 测试服务器只允许 PostgreSQL SSL 5432、Redis TLS 6380 和 RabbitMQ TLS 5671 公网访问。
  Redis 明文 6379、RabbitMQ 明文 5672 和管理端 15672 禁止公网访问；5672 和 15672 仅可按现有依赖保留本机监听。
- PostgreSQL 共用 `zhixiaohe_test`。Redis 共用测试实例，但 Key 必须保留环境前缀，禁止绕过统一 Key 构造。
- 多个开发者本地进程禁止竞争同一个正式消费队列。联调时队列名增加稳定的开发者后缀，或者明确约定
  同一时刻只有一个共享 Python Worker 消费；临时 Gate 队列必须在测试结束后删除。
- 暂未开始 Java/Python 联调前，不预建大量正式业务交换机或队列。

## 测试环境公网风险接受与生产前置条件

当前公网开放是负责人为提高测试阶段开发效率作出的显式风险接受，仅限光禾AI智能体框架测试服务器和测试账号。
TLS、SCRAM、Redis ACL、RabbitMQ VHost 隔离和最小命令/对象权限用于降低风险，但不等同于生产级网络隔离。

生产部署前必须全部完成：关闭 PostgreSQL、Redis、RabbitMQ 的公网直接访问；重新设计并验证安全组、
主机防火墙、私网或受控接入边界；撤销测试环境证书和账号；轮换 PostgreSQL、Redis、RabbitMQ、JWT
及其他全部密码和密钥。未经这些整改，不得复制当前测试网络策略到生产环境。

## 账号密码认证接口

- `POST /api/v1/auth/login`：匿名登录，账号大小写不敏感。
- `GET /api/v1/auth/me`：携带 `Authorization: Bearer <access-token>` 查询当前账号。
- `POST /api/v1/auth/logout`：递增账号 `token_version`，使该账号此前签发的访问令牌全部失效。

首次登录或管理员重置后的新密码最低为 6 位，最多 128 位；原有较长密码继续有效。
此长度规则不缩短系统生成的临时密码，也不改变密码哈希、激活令牌、协议确认或登录限流。

管理员开户复用 `POST /api/v1/admin/users`，`phone` 与 `username` 必须且只能填写一种。
用户名为 3–32 位 ASCII 字母开头的字母、数字或下划线，按大小写不敏感的规范化值唯一；
纯用户名账号的手机号为 `null`，不会填充虚构号码。开户仍只创建 USER，系统生成一次性展示的随机临时密码，
首次登录须改密并接受协议；初始积分、对应流水和审计与账号在同一事务内写入，重复开户不重复赠送积分。
列表、详情及开户响应返回真实 `username` 与可空 `phone`，查询支持用户名、手机号与昵称。
V044 仅移除账号手机号非空约束，保留原手机号唯一约束和所有既有数据；部署前通过
`scripts/run-isolated-database-gate.ps1 -UsernameProvisioningOnly` 做 V043→V044 及真实事务验证。

JWT 包含字符串形式的 `userId`、`role`、`tokenVersion`、`iat` 和 `exp`。每次鉴权都会重新读取账号状态、
角色和 `token_version`。本版本没有刷新令牌、手机号注册、短信验证码、忘记密码或第三方登录。

## 数据库迁移模式

只有负责人或指定开发者执行已评审迁移时才启用 Flyway：

```text
SPRING_PROFILES_ACTIVE=dev
SPRING_FLYWAY_ENABLED=true
FLYWAY_DB_URL=jdbc:postgresql://<服务器公网 IP>:5432/zhixiaohe_test?sslmode=require
FLYWAY_DB_USERNAME=zhixiaohe_test_flyway
FLYWAY_DB_PASSWORD=<由负责人保管>
```

应用连接池仍必须通过 `APP_DB_*` 使用 `zhixiaohe_test_app`，不得把迁移账号当作普通开发账号。
数据库密码只放在本地环境变量或受限配置中，禁止提交 Git。

## 数据库迁移纪律

- 所有结构变更只通过 `src/main/resources/db/migration` 下的 Flyway 迁移完成。
- Flyway 迁移文件必须提交 Git；禁止手工修改共享测试库结构来绕开迁移记录。
- 已在共享环境执行的迁移禁止修改，只能新增更高版本的修正迁移。
- 新迁移先评审、再由负责人或指定开发者执行；未经评审不得直接对共享测试库运行。
- 应用连接池只使用 DML 运行角色；Flyway 使用独立迁移角色，禁止共用高权限账号。
- 业务模型使用“账户 → 经营空间 → 商品/竞品”，不存在店铺表。
- 通用 Flyway 路径只包含环境无关的结构迁移，禁止放入固定测试账号或其他已知凭据。
- 固定测试账号脚本位于 `src/test/resources/db/testdata`，只能由显式测试 Gate 使用迁移账号执行；
  Gate 和 SQL 都会在写入前校验 `current_database()` 必须完全等于 `zhixiaohe_test`。

## 契约

Java/Python 与浏览器事件的版本化契约位于 `src/main/resources/contracts`：

- `hmac/hmac-v1-test-vector.json`
- `rabbit/task-execution-requested-v1.schema.json`
- `sse/conversation-event-v1.schema.json`

契约中的业务 ID 均为十进制字符串；Rabbit 消息只携带执行元数据，不携带 Prompt、正文、凭据或完整任务参数。

对话执行中的 Python `run.progress` 只作为用户安全的阶段动态：Java 仅接受冻结白名单阶段和状态，
补齐权威 Run、消息及发生时间后持久化为 Browser `agent.progress`。同一 Run 按
`(stageCode, status, ordinal)` 幂等去重且最多保留 24 条，并继续沿用会话 sequence 做断线回放；
非法、乱序或超限进度只记录脱敏诊断并丢弃该事件，不得中断正常 Run，也不得把模型思考过程、Prompt、
工具参数、Provider 响应或异常堆栈暴露给浏览器。

### GEO 业务基础资料

Java 通过 `/api/v1/geo/topics` 维护 USER 自有的 GEO 专题，以及按用户输入顺序整体替换的正式关键词、
正式问题和版本化竞品集合。专题只能绑定当前用户经营空间，商品非空时必须属于同一空间；问题首版只接受
用户手工保存的 `BRAND_DISCOVERY / MANUAL`。AI 对话也可先生成仅供核对的 `QUESTION_DISCOVERY`
专题与 3～10 个问题；草稿阶段不写 GEO 配置，用户执行一次付费确认后，Java 才在同一事务内创建专题和问题、
转换为正式 `MONITOR_RUN` 并冻结积分。每次竞品集合保存都创建新的不可变版本，
历史版本不覆盖；当前版本按专题内最大版本号读取。专题名称、关键词和问题均复用 Catalog 的 NFKC、去首尾
空格和大小写无关规范化规则。

GEO `MONITOR_RUN` 复用现有付费任务确认、积分冻结、Outbox/Inbox、执行租约和 SSE 状态机。Java 在确认前
冻结 Topic、问题、平台、竞品集合及经营资料快照；Worker 只能通过 HMAC Internal API 分条写 Observation，
最终状态及结算由 Java 根据已落库成功单元决定。报告只表达本次 AI 回答样本中的提及信号，不表示市场份额。
GEO 执行由 `GEO_EXECUTION_ENABLED` 单独控制；兼容 Worker 未就绪时保持关闭，不允许创建无消费者的任务。

降级报告的智能解读恢复由 `GEO_INTERPRETATION_ENABLED` 单独控制（默认关闭，启用前需完成 V041 迁移并部署支持 `/internal/v1/geo/syntheses` 的 Python）。用户显式 POST `/api/v1/geo/reports/{reportId}/reinterpretation` 后只使用原始已保存回答、引用与冻结目标；GET 不触发模型。每份报告最多 2 次模型恢复尝试、每次最多 2 次模型请求，账户同时最多 1 份报告恢复；成功报告不会重新生成。Java 独立单 worker 执行、无内存等待队列，QUEUED 重启后续跑，RUNNING 90 秒租约失效转失败且不自动重试；模型/HTTP 时限为 60/70 秒。仅明确未调用模型的容量 429 可以同尝试排队 5 秒后重投、最长等待 2 分钟；容量等待超时后用户可手动重试，不消耗模型次数，其他失败与未知结果都消耗该次额度。所有失败保留原报告，不新建付费任务、不重采集、不改变采集时间和积分。请求含有界证据节选及基于完整回答的确定性名称提及投影，原文保持不变。
测试报价由 `GEO_TEST_PRICING_VERSION_ID` 与 `GEO_TEST_UNIT_POINTS` 显式配置，后者是每个
“问题×平台×轮次”成功采样单元的测试积分，不是生产定价。可选平台由
`GEO_CONFIGURED_PLATFORMS` 配置，Budget 使用 `GEO_MAX_PROVIDER_CALLS`、`GEO_MAX_CONCURRENCY` 和
`GEO_EXECUTION_DEADLINE`。报价前按问题、平台、轮次及最大重试次数校验调用预算；DeepSeek 和 Kimi 每次采样预留搜索与回答两次调用，其他平台预留一次原生搜索调用。积分仍按成功采样单元计费。Java 会把该预算推导出的可执行问题数上限
写入授权 Run Context；Python 只能在该上限内生成草稿，不再维护另一份静态限制。PostgreSQL 仍是 Run、
Observation、Citation、Report 和积分的唯一真相源。

GEO 完成结算使用确认时的总积分除以冻结采样单元数还原单价，再乘实际成功单元数；
运行中修改 `GEO_TEST_UNIT_POINTS` 不会重定价已确认任务。快照无法整除或相互矛盾时拒绝结算，
不以当前配置补齐。部分成功释放剩余额度，重复完成仍复用现有事务与幂等保护。

GEO、文章、图片/视频资产及媒体发布的实际 `TASK_SETTLE` 记账时间，均在取得现有
积分账户行锁后读取 `billingClock`。等待账户锁跨过北京时间午夜时，消费归入取得锁后的日期；
同次部分结算与释放使用同一记账时间，纯释放路径及任务事件、租约、报告的业务时间保持不变。
此变更不改积分金额、接口或数据库结构，也不改写历史账本；每日自动监测预算与自动执行授权仍需后续实现。
执行 Context 的 deadline 以 GEO Run 首次 `startedAt` 为起点，重试租约不会重置起点。
本阶段时长仍读取 `GEO_EXECUTION_DEADLINE`，并未增加持久化 deadline 字段；运行中不要修改
该时长。跨发布固定期限和外部尝试预算的完整持久化属于后续 P1，不改数据库结构。

`geo-deterministic-v1` 表示采集结果已保存但 AI 分析未完成：报告归类为部分完成，禁止用于文章工作流或文章生成；导出仅保留采集证据。成功采样的既有结算口径不变。

媒体目录默认容量为 500 页 × 100 条，仍要求分页总数、唯一资源数和完成数一致。`MEDIA_RESOURCE_SYNC_CRON` 默认在北京时间 02:05、12:05、19:05 自动补齐，避开上游关闭时段；`MEDIA_MAX_SYNC_PAGES` 的服务器显式配置优先。管理端“可用资源”只统计当前供应商、支持的资源模型且价格快照未过期的 ACTIVE 资源，不代表投稿执行开关已经开启。

GEO 自动计划由 `GEO_PLAN_ENABLED` 单独开启，使用 Quartz JDBC JobStore 和 `business.qrtz_*`
持久表。计划只接受 `DAILY`、`WEEKLY`、`INTERVAL_DAYS` 结构化周期，不接受任意 Cron；
`GEO_PLAN_MIN_INTERVAL_DAYS` 与 `GEO_PLAN_MAX_INTERVAL_DAYS` 限制间隔周期。确认计划仅消费一次性
确认令牌并保存不可变授权快照，不冻结积分。到点后以 `(planId, scheduledSlot)` 数据库唯一约束防重，
再复用现有 GEO Task、积分、Ledger、Event、Outbox 和 RabbitMQ 链路。余额不足或超过单次积分上限会
留下 `SKIPPED` 槽位并暂停计划；恢复只计算新的未来触发点，不补跑暂停期间的历史槽位。

### IMAGE / VIDEO 付费任务测试开关

图片任务支持一次选择 1–4 张候选图。Java 按所选张数生成总报价并冻结积分；真实结果全部完成摄取后，
按永久入库成功数结算，未生成或入库失败部分退回积分。多图结果使用 `assets` 与数量字段，仍兼容历史
单图 `asset`；部分成功标记 `PARTIAL / IMAGE_PARTIAL_COMPLETED`。部署本功能前必须先应用 V033，
再更新 Java、Python IMAGE Worker，最后发布 Web；V033 替换单任务资产唯一约束，不能只回退旧 Java
二进制而保留新库结构。回退需协调备份恢复，不能覆盖新版本已接收的用户数据。

创作表单支持 `TEXT_ONLY`（不引用商品或图片）、`SELECTED_ASSETS`（同用户同空间已就绪图片）、
`CATALOG_PRODUCT`（商品身份图）。视频还支持 `PRODUCT_MATERIALS`（该商品绑定的 1–3 张素材，
允许 DETAIL/PACKAGE）和 `GEO_REPORT`（同商品同空间已完成报告与商品首帧）；报告的真实结论、
建议、缺样和方法版本在报价时冻结，确认前重新校验归属，不接受浏览器自造报告内容。
旧 AI 对话的附件优先和商品身份来源规则保持不变。纯文本任务不会借用所选空间中的商品事实。

`REGION_EDIT` 只接受 1 张底图和独立 `maskAssetId`，均须同用户同空间 READY；底图和黑白 PNG
蒙版须同尺寸，白色区域表示重绘，黑色区域保持原像素，至少有一个白色像素。启用
`IMAGE_REGION_EDIT_ENABLED=true` 前必须确认兼容 Worker 和 `WAN_IMAGE_EDIT/wanx2.1-imageedit`
已就绪；默认关闭时在报价/确认前拒绝，不会冻结积分。蒙版不混入普通参考图列表。
底图为 512–4096 像素、10MiB 以内 PNG/JPEG；带非正常 EXIF 旋转/镜像的底图在报价前拒绝，
提示用户按可视方向另存 PNG，不能让浏览器与服务端的坐标系错位。入库前按蒙版合成原图，
黑色区域逐像素保留原图 RGBA；生成结果尺寸不同时仅缩放重绘层。V034 扩充对应入库约束，
不改变既有 Qwen/视频的严格 MIME 和几何校验。
其余图片沿用 Qwen，视频沿用 `WAN_VIDEO/wan3.0-video`；Provider 结果及 UNKNOWN 恢复均必须
匹配已冻结来源，不允许把另一模型结果结算到该任务。
默认 `PAID_TASK_ENABLED=false`；
显式联调时必须通过环境变量提供独立的 `TASK_CONTROL_TOKEN_SECRET`、正数
`IMAGE_MOCK_PRICING_VERSION_ID`、`IMAGE_MOCK_POINTS`、`VIDEO_TEST_PRICING_VERSION_ID` 和
`VIDEO_TEST_POINTS`。这些价格变量只代表可审计的测试报价，
不是正式产品价格，浏览器和 Python 都无权提交或覆盖报价。Outbox 发布另由
`TASK_OUTBOX_PUBLISHER_ENABLED=true` 显式开启，并复用既有 RabbitMQ TLS 连接和共享消息 Schema。
Qwen 返回的是带有效期的临时 Provider URL，不是永久资产。启用资产摄取后，Java 先把任务保持在
`ASSET_INGESTION_PENDING`，在事务外流式下载、校验并写入 OSS；只有永久资产落库后才结算积分并
完成任务。Provider URL 只保存在受限摄取记录中，不能进入 Browser DTO、日志或错误响应。上游费用
未报告时按未知记录，不得把兼容字段中的零值解释成真实云服务费用。
真实密钥和测试报价均不得写入 Git；上线正式价格前需接入独立、已评审的价格版本来源。

开启 Outbox 发布时，Spring AMQP 会声明持久化的 `zhixiaohe.task` Direct Exchange、
`zhixiaohe.agent.image` / `zhixiaohe.agent.video` / `zhixiaohe.agent.geo` /
`zhixiaohe.agent.article` Queue，以及对应的 `.dlq` 死信队列；路由键分别为
`agent.image`、`agent.video`、`agent.geo` 和 `agent.article`。拓扑沿用现有 RabbitMQ
连接和管理器，不创建第二套客户端。

付费任务启用时，执行租约 Watchdog 默认同时启用。它以 `TASK_EXECUTION_LEASE` 判断失联，
将旧执行代次标为 `EXPIRED`，通过新 Outbox 事件递增 `executionNo` 后重新投递；旧代次 Token
不能再心跳或回报结果。`TASK_MAX_EXECUTION_ATTEMPTS` 默认是 2，达到上限仍失联时由 Java
释放冻结积分并终结任务。测试环境可用 `TASK_EXECUTION_WATCHDOG_ENABLED` 和
`TASK_EXECUTION_WATCHDOG_POLL_DELAY_MS` 控制 Gate，不能由 Python 决定重试或积分结果。
同一 Watchdog 还会按 `TASK_MAX_QUEUE_AGE`（默认 30 分钟）条件终止从未被 Worker Claim 的陈旧
`QUEUED` 任务，以 `DISPATCH_EXPIRED` 留存审计事件并一次性释放冻结积分；迟到消息只能得到
`TERMINAL`，不会重新执行。测试环境短时调整该值完成恢复验证后必须恢复默认值。

Qwen 已提交成功但轮询结果暂时不确定时，Worker 必须以 `submissionState=UNKNOWN` 回报完整的
Provider、模型、`providerRequestId` 和 `providerTaskId`。Java 将这些 continuation 信息保存在
当前 `task_execution`，保持积分冻结，并以新的 `executionNo` 和 Outbox 有界重投。恢复 execution
的 Context 固定返回 `resumeOnly=true`，Worker 只能轮询既有 Qwen/Wan 任务，不能再次提交媒体生成。
每次恢复仍受 `TASK_MAX_EXECUTION_ATTEMPTS` 限制；达到上限后 Java 将任务标记为系统终止、释放
冻结积分并写入终态事件。Provider 内部 ID 不进入 Browser API、Browser SSE 或应用日志。

### ARTICLE 文章工作区与付费生成契约

文章当前标题、提纲和正文由 PostgreSQL `business.article` 保存为权威状态，正文写入前由服务端
HTML 白名单清洗并派生纯文本。文章插图只引用同一用户、同一经营空间下已就绪的 IMAGE 永久资产；
投稿使用不可变的 `article_submission_snapshot`，不会把 Provider 临时 URL、OSS Object Key 或
Base64 内容保存到文章或 Browser 响应。

ARTICLE 生成复用现有 TaskDraft、确认令牌、积分冻结/结算、Outbox/Inbox、执行租约、HMAC Internal
API 与任务 SSE。阶段固定为 `TITLE / OUTLINE / BODY / REWRITE`，每次实际模型调用都是一次单独报价和
确认的付费任务。`ARTICLE_EXECUTION_ENABLED` 在兼容 Python Worker 就绪前必须保持 `false`；测试报价
由 `ARTICLE_TEST_PRICING_VERSION_ID` 和四个 `ARTICLE_*_TEST_POINTS` 显式配置，不代表生产价格。
Worker 结果使用受限 `ARTICLE_CONTENT` 结构，Java 校验文章版本、提纲或改写范围后才更新权威文章并
结算积分；供应商没有报告金额时只记录“未知/未报告”，不能解释为免费。

AI 对话中的新文章请求仍然只是 proposal-only。Python 只能提交主题、语气和长度等编辑建议；Java 在
同一事务内校验用户、经营空间和商品归属，创建权威 DRAFT 文章工作区，再补齐 `articleId`、版本和
`TITLE` 阶段参数并生成待确认 TaskDraft。模型不得自行生成文章 ID，也不能通过成功话术代替草稿落库。

同步 DeepSeek 请求发出后若超时、传输结果不确定或执行租约过期，不能自动重发文章生成。
Worker 的 `ARTICLE_DEADLINE_EXCEEDED` / `DEEPSEEK_SUBMISSION_UNKNOWN` 以
`submissionState=UNKNOWN`、`provider=DEEPSEEK` 和两个显式空回执上报；Java 保存不确定状态，
将无可查询回执的任务收口为 `SYSTEM_TERMINATED` 并只释放一次冻结积分。旧 watchdog 重试消息
也不能再次提交。IMAGE/VIDEO 的原有回执恢复和 GEO 的既有重试策略不变。

`ArticleFullPreparation` 是未来 `ARTICLE_FULL_V1` 的只读准备值：复用归属检查、报告快照、阶段报价
和预算，冻结一篇文章的 `TITLE/OUTLINE/BODY` 三阶段输入、总报价、时限与哈希。它没有公开接口，
不创建文章/任务/确认令牌，不扣费，也不代表执行授权。持久化准入、自动采纳标题/方案、同一文章
逐阶段执行及发布媒体推荐尚未接通；当前用户流程仍按原有阶段确认。

### 媒体资源、报价与投稿

媒体投稿复用现有 TaskDraft、一次性确认令牌、积分账户/流水、`business_task` 和 Transactional
Outbox；它由 Java 本地后台执行，不进入 Python Agent 或 RabbitMQ 任务队列。媒体资源来自受控的
采购平台同步，第一版只允许已验证的 `model_id=1`。报价固定使用
`saleYuan = ceil(upstreamPrice × 1.35)`，再按 `saleYuan × 100` 得到分和积分；上游原价只保存在
数据库报价快照中，不由浏览器提交或重新计算历史订单。

投稿确认写入本地 Outbox 时，JSON 中的数字 `attempt=1` 必须与该行 `execution_attempt=1`
保持一致，遵守既有 V005 约束；它不是后台调度重试次数 `attempts`。确认事务统一创建任务、
冻结积分、投稿批次/子订单和 Outbox，任一步失败均整体回滚。2026-10-14 修复漏传该字段导致
确认接口 500 的问题，不修改已应用迁移、接口契约、报价或供应商协议。新增隔离数据库验证入口
`scripts/run-isolated-database-gate.ps1 -MediaSubmissionOnly`，使用测试供应商替身验证确认、
幂等重放与真实约束失败后的完整回滚，不连接真实媒体供应商。

媒体能力默认关闭。启用时使用 `MEDIA_ENABLED`、`MEDIA_RESOURCE_SYNC_ENABLED`、
`MEDIA_SUBMISSION_EXECUTION_ENABLED` 和 `MEDIA_ORDER_POLL_ENABLED` 分离只读同步、真实投稿与查单。
`MEDIA_API_BASE_URL` 和 32 字节 ASCII `MEDIA_API_APP_KEY` 只允许进入受限环境文件。没有明确批准的
无副作用沙箱时，真实投稿开关必须保持关闭；允许执行资源和价格只读探测，但不得用生产账号创建订单。

报价使用本地已同步资源快照（默认最长 36 小时），不在报价或确认时调用上游资源接口；确认事务内锁住资源行并核验
业务版本、状态和价格。资源变更回调或后续同步使旧报价失效时返回 `MEDIA_QUOTE_CHANGED`，不消费
确认令牌也不冻结积分。真实提交的传输结果不确定时进入 `SUBMIT_UNKNOWN`，禁止重新提交。
旧采购平台查单只验证了 `model_id + order_id`（其中 `order_id` 是上游返回的
`resource_order_id`）；没有上游订单号时不猜测性查单，等待已验签回调或人工核对。
四个回调事件同时开放版本化路径 `/api/v1/callbacks/media/*`
和旧 GEO 转发兼容路径 `/api/callback/mt-*`；两组入口复用同一套 AES-256-CBC/PHP serialize 协议验签、
解密、幂等和状态机。日志不得记录 AppKey、完整上游请求/响应、
文章正文或发布地址；Browser API 只返回用户安全的资源价格、投稿状态、积分和公开发布回链。
发布回链来自回执的 `return_info`，支持多个 HTTP(S) 地址；`return_file` 是附件，不作为回链。
上游签名回执包含非空 `return_info`、`return_content` 或 `return_file`，且不是进度或退稿报文时，
即使 `return_info` 只有文字或暂时没有公开链接，也按发布终态结算；
此时用户端显示发布成功但不伪造回链。
图文投稿复用旧 GEO 实际使用的 OSS 图片＋`prop.neirong` HTML 协议，不等待重新对接供应商。
开关 `MEDIA_IMAGE_SUBMISSION_ENABLED` 默认 false；启用需要设置 `MEDIA_IMAGE_CONTRACT_VERSION`
（例如 `legacy-html-20261009-v1`）、图片数量（默认最多 10）和大小（默认及上限 10 MiB）。
PNG/JPEG 通过现有素材上传和引用；公开副本的对象 ACL 必须允许匿名读取，不会自动放宽桶权限。
资源明确返回“免费/不收费/0”或“N元/张、N元/篇”时使用该规则；明确禁图始终优先，
空值、仅“收费”和无单位数字不会被视为免费。`image-pricing-rules` 可按 `modelId:resourceId`
设置已核验 `mode/amount/version`（INCLUDED、PER_IMAGE、PER_ARTICLE）；只有显式配置
LEGACY_PER_IMAGE 才将无单位数值沿旧规则解释为按张费用。配置不是浏览器或模型输入。
报价将基础媒体价和本稿图片费合计后，按既有加价/取整公式计算一次总积分；推荐与最终报价均显示
基础费、图片数、计费方式、图片费差额及总价。规则版本、金额、正文或图片变化使旧确认失效。

报价在内部 TaskSnapshot 冻结正文版本/哈希、图片 ID、字节哈希、大小、类型和规则版本；确认时重新核验。
V058 只在用户确认后为订单固定同用户同空间的资产引用，外键阻止删除、触发器阻止替换已投稿字节；
编辑原文章不改历史订单。后台在原提交幂等状态机内校验 OSS 实际字节，将正文原位的
`figure[data-asset-id]` 转为 `<img>`，保留标题/段落/列表/表格和图片说明；不存在正文位置的配图、
未授权 ID、任意浏览器图片 src、非 READY 或不可使用安全状态均拒绝，不静默丢图。
V059 在确认后的订单图片引用中固定独立随机发布路径 `media-public/<environment>/<256-bit-random>.png|jpg`。
新上传资产通过 PutObject 显式设置 Private，不能继承旧桶的 PublicRead。历史对象和桶 ACL 不自动改写。
后台先验证原图字节，再幂等写入发布副本，仅将该副本设为 PublicRead；发布过程不修改原图或桶 ACL。
匿名 HTTPS GET 的长度、类型和 SHA256 全部一致才提交供应商。发布 URL 无签名、无短期有效期，
编辑原文章/替换资产不改变旧发布副本；普通素材清理不得删除发布前缀，OSS 生命周期也须排除此路径。
公开路径只给已确认订单使用，不在 Browser API/日志暴露对象 Key；数据库禁止改写或删除已确认 pin。
生成链接/核验失败发生于供应商调用前，按未提交失败释放积分；供应商调用后 UNKNOWN 仍禁止重投，只查原订单。
此实现不代表图文渠道已完成真实投稿验收，开启前仍须进行明确批准的渠道验收；不得靠测试下真实订单探测能力。
正文含外链时，所选媒体必须明确允许外链；否则同样在冻结积分前被拒绝。
用户从任务通知进入详情时，可按所属 `taskId` 查询本人投稿及其回链。

正文进入 `BODY_READY / EDITING / READY_TO_SUBMIT` 且非空后，Browser 可按文章读取最多 5 个媒体
候选。Java 仅根据已确认创作方案的目标平台和 ACTIVE 资源元数据做确定性排序，返回推荐理由、当前
展示价、预计积分和有效期；未命中目标平台的条目只标记为“当前可用候选”，不宣称投放效果。用户仍可
从完整资源列表人工调整选择。一次点击投稿由 Web 串行复用投稿快照、媒体报价和通用确认接口；Java 在
报价、确认以及创建投稿批次前持续核验正文版本和状态，并以最终 `quotedPoints` 为准，避免重复扣分或
重复下单。

资源同步要求上游显式返回总数，且各页总数一致、资源 ID 不重复、最终条数与总数一致；校验通过后
分短事务写入。较旧的同步页不能覆盖较新的资源回调或停用决定，完整同步成功后
自动停用本次未返回的旧资源。同一资源重复同步不会创建重复记录，权威字段没有变化时
也不会虚增业务版本。同步状态按模型独立记录稳定错误码。`MEDIA_RESOURCE_SYNC_ENABLED=true` 只开启
只读资源同步，不会隐式开启投稿或查单；上游若返回访问时段限制，Java 保留
`MEDIA_PROVIDER_SCHEDULE_RESTRICTED`，不得把拒绝响应解释为同步成功。

### 多步骤工作流计划基础

V015 提供复杂目标的 proposal-only 计划层：Python 通过现有 HMAC、Nonce 和 Trace 保护的
`POST /internal/v1/runs/{runId}/workflow-plans` 提交 2～12 步 DAG，Java 校验 Run Context、用户与
会话归属、工具白名单、风险级别和总预算后，在一个事务中保存计划、步骤、确认预览、审计事件以及
多个现有 `TaskDraft`。当前可授权工具只有 QUERY/ANALYZE/PREPARE，PREPARE 复用 IMAGE、VIDEO、
GEO、ARTICLE 既有参数校验和 Java 报价；不接受 COMMIT 工具，也不允许 Python 提交价格、Provider、
模型、用户或角色。

P2A 只把计划推进到 `WAITING_CONFIRMATION`，确认预览明确标记 `consumptionEnabled=false`。因此它不会
消费确认令牌、冻结积分、创建 `business_task`、写执行 Outbox、投递 RabbitMQ 或调用 Provider。
用户可通过 `/api/v1/workflow-plans` 查询安全投影并放弃尚未确认的计划；放弃会在同一事务中使计划和
关联草稿确认失效。工作流计划与草稿的权威状态全部保存在 PostgreSQL，浏览器响应不返回确认令牌
哈希、Run Context Ref、内部工具参数或其他敏感载荷。

V016 增加计划级授权，但仍不启用执行。`POST /api/v1/workflow-plans/{planId}/confirmation-preview`
是唯一返回明文计划确认令牌的接口；`POST /api/v1/workflow-plans/{planId}/confirm` 使用
`Idempotency-Key`、精确计划版本和用户授权积分上限完成原子确认。确认只持久化 `CONFIRMED` 及审计，
不冻结积分、不创建任务、不写 Outbox、不投递消息且不启动步骤；响应固定声明
`executionEnabled=false`、`executionStarted=false`。

### P3 工作流执行控制面（默认关闭）

V017 只建立可审计的 Java 控制面，不代表 Python Worker 已可执行真实工作流。计划、步骤、执行代次、
租约、Observation 和事件均以 PostgreSQL 为真相源：Python 负责 QUERY/ANALYZE/SYNTHESIZE 的受限执行，
PREPARE 继续复用 Java 现有 TaskDraft 校验与报价；任何正式 Task、积分结算和 Provider 副作用仍只能由
Java 既有 Task 控制面决定。服务器必须保持 `WORKFLOW_EXECUTION_ENABLED=false`，直到 P3B/P3C 的兼容
Worker、任务资金划拨与联合 Gate 完成。

`POST /api/v1/workflow-plans/{planId}/start` 只有在执行开关和兼容 Worker readiness 同时满足时才生效；
否则稳定返回 503 且不冻结积分、不写 Reservation/Execution/Event/Outbox。成功启动以计划的 Java 报价为
唯一资金依据：冻结的是整张计划的授权额度，记录在 `workflow_point_reservation`；后续 PREPARE 转正式
Task 时必须从该 Reservation 原子划拨，禁止再次从用户可用积分冻结。该划拨及按 Task 结算属于 P3B，
当前控制面终态只释放尚未划拨的冻结额度。

每次 Worker Claim 创建新的 `workflow_execution.execution_no` 和不可回放令牌摘要；Heartbeat、步骤状态、
Observation、Replan 和 Completion 都必须匹配当前代次及未过期租约。租约超时后 Watchdog 先使旧代次
EXPIRED，再有界重投；旧令牌不能写 Observation 或终态。达到最大代次后计划 FAILED，并仅一次释放尚未
划拨的积分。Observation 是 append-only，以 eventId、toolCallId、步骤 attempt 三重唯一规则约束；消息仅含
计划、版本、代次和 Trace 等安全引用，不含正文、授权上下文、凭据或 Provider 标识。

Replan 首版最多一次，只允许修改仍为 PENDING/READY 的步骤依赖、成功标准和引用，不允许新增步骤，也
不能改变工具、风险、报价、已运行/已完成步骤或扩大副作用。任何扩大成本、风险、步骤或 Provider 调用的
请求必须进入 `RECONFIRMATION_REQUIRED` 并重新获得用户授权；这条完整交互在 P3C 实现。`WAITING_INPUT`
是显式耐久边界，不等于失败或自动继续；恢复用户输入同样留给 P3C。当前实现不调用真实 Provider。

V018 补齐 P3C 的 PREPARE→正式 Task 桥和资金分账：计划确认时固定每个 PREPARE 草稿的 input hash、
报价版本及有效期；兼容 Worker 只能使用当前 workflow execution token 调用既有 Task 控制面创建任务。
任务额度从 `workflow_point_reservation` 的已冻结余额原子划拨，禁止再次冻结用户积分。Task 进入终态后，
Java 按既有任务账本结果把该份额度镜像为结算或释放，随后创建带 `resumeOnly=true` 的下一执行代消息；
旧 workflow execution token 已进入 `WAITING_TASK`，不能再写步骤或终态。Task 成功、失败、部分成功及
重复回报均沿用现有 Task 幂等和终态规则，工作流上下文只返回安全的 Task 引用，不返回 Provider 标识。
在兼容 Python Worker 完成并通过联合 Gate 前，测试服务器必须保持
`WORKFLOW_COMPATIBLE_WORKER_READY=false`，浏览器启动工作流稳定返回 503 且不产生资金或消息副作用。

V019 为每个新 Workflow Plan 冻结 Java 授权的不可变 Context：经营空间、商品、当前 ACTIVE 竞品、
按预算裁剪的会话记忆以及任务工作区均来自原始 Run 的 PostgreSQL 快照。Redis 只参与原始 Run 工作区的
可重建加速，计划创建后不再用经营资料当前值覆盖历史语义；后续 execution 代次只叠加 Java 权威的
Step、Task 和 Observation 状态。V019 之前的计划明确标记 `data gap`，未开始计划必须重新生成，终态计划
仍可读取。授权 Context 超限时先裁旧消息，再裁低优先级竞品与别名，仍超过 192 KiB 时稳定拒绝，确保
完整 execution Context 不触碰 256 KiB 数据库上限。

Browser 计划步骤只增加 `taskId`、`taskStatus` 和 `taskResultRef` 三个安全交付引用；不会返回 Provider
URL/ID、OSS Object Key、execution token 或完整授权 Context。Web 需要据此做增量展示对齐，但在兼容
Worker 联合 Gate 通过前仍不得启用计划执行入口。

### 对话受理、恢复与精确操作

聊天消息继续复用 `clientMessageId` + `Idempotency-Key` 的持久幂等；Java 在同一会话行锁下检查
重放和活跃 Run。未知提交结果先查 `GET /api/v1/conversations/{id}/message-operations/{clientMessageId}`；
未查到不代表原请求绝不会提交，重试必须保留原消息内容及两个标识，不能换键再发。

刷新先读 `GET /api/v1/conversations/{id}/snapshot?limit=50`，获得同一 PostgreSQL 快照中的最近消息、
`nextBeforeSequence`、`activeRun` 与 `highWatermark`；再以此 highWatermark 作为 SSE `Last-Event-ID`。
历史页使用 `/messages?beforeSequence=...`。消息列表都按 sequence 升序；每段回复及对应事件同事务保存，
失败和停止保留已保存残文。读快照、查受理结果、补历史和重连均不重新启动模型。

原生对话正文继续使用 `message.delta`，默认追加；可选严格布尔 `replace=true` 将当前 Run 的整段回复
替换为 `delta`（允许空字符串清除工具调用前或协议纠错前的临时文字）。Java 在同一事务保存替换后的
正文及事件，再同步更新桥接累积文本，断线回放遵循原有 sequence。未完成/停止仍保留最后已提交正文，
不会自动重试模型，也不能改写其他消息、已终结回复或任务。浏览器须同步支持替换，不能再拼接旧快照。

停止请求必须携带 `{ "expectedRunId": "..." }`，只作用于该用户该会话的指定免费回复；旧回复的停止
不会命中新回复，也不取消付费任务。旧 `confirm-active-task-draft` 快捷确认入口固定失败关闭；
必须使用具体 draftId 和展示过的完整确认凭据提交，不能由 Java 替旧客户端猜测当前报价并生成凭据。

聊天受理现在先锁当前有效账号，再锁会话；同账号多会话共享免费配额：默认同时 2 个 Run、滚动
60 秒 10 轮、上海自然日 200 轮（其中当前消息图片 40 轮）。环境变量依次为
`CONVERSATION_MAX_CONCURRENT_RUNS`、`CONVERSATION_MAX_TURNS_PER_MINUTE`、
`CONVERSATION_MAX_TURNS_PER_DAY`、`CONVERSATION_MAX_VISION_TURNS_PER_DAY`。免费对话不扣积分；
成功受理后失败/停止仍占一轮，同键重放不重计，删除历史也不恢复额度。受理前校验失败不占额度。

发布前必须执行 **V040**：`conversation_admission` 仅保留账号、Run ID、受理时间和是否带图，
独立于可删除的会话，升级回填仍存在的近两日 Run。新 Run 的 `deadline_at` 在受理时冻结，不因
延迟启动/重启延长；老 Run 以既有 context 到期时间兜底。`CONVERSATION_RECOVERY_ENABLED=true`、
`CONVERSATION_RECOVERY_GRACE=30s`、`CONVERSATION_RECOVERY_POLL_DELAY_MS=5000` 控制定时扫尾，
每批最多 20 条。超时残留 Run 标记 FAILED（已请求停止则 STOPPED），保留残文；不会重启不确定的
模型调用，也不取消付费任务。停止后合法迟到的已知用量可继续落库，不重新发业务事件或改变终态。
现有 usage 契约对缺失供应商 token 仍补零，因此缺失不能解释为实际零成本。

### OpenTelemetry 追踪基础

Java 使用 Spring Boot Actuator、Micrometer Tracing 的 OpenTelemetry bridge 和 OTLP exporter，未创建
第二套 SDK。`OTEL_TRACING_ENABLED` 默认 `false`；测试服务器显式设为 `true` 并以
`OTEL_TRACING_SAMPLING_PROBABILITY=1.0` 全量采样，OTLP HTTP endpoint 只指向 loopback Collector。
Exporter 使用短连接/导出超时，Collector 暂时不可用只丢失遥测，不得阻塞或改变业务事务结果。

现有 `TraceIdHolder`、HTTP `X-Trace-Id`、W3C `traceparent`、HMAC 请求和 RabbitMQ 消息头保持兼容。
HTTP 入站沿用真实父上下文；Java 调 Python、SSE 持久化、Task/积分/Outbox、资产摄取和媒体流程创建
受控子 Span。后台恢复没有可信父上下文时创建新 Trace，并只用散列后的业务引用关联审计记录。
Span 属性使用固定白名单，禁止正文、Prompt、JWT、HMAC Secret、AppKey、Provider 内部 ID、数据库参数、
完整 URL、签名参数或原始异常进入遥测。应用日志的关联字段只有 `traceId` 和 `spanId`，不另造第二套 ID。

Java 调 Python 的普通 JSON 接口失败时，沿用该 Trace 输出
`python_internal_call_unavailable`。只记录固定接口类别、失败类别、既有失败原因和已观察到的
HTTP 状态，不记录异常正文、堆栈、请求/响应或动态地址。可区分 HTTP 拒绝、超时、资源访问异常、
响应读取/解析异常及其他客户端运行异常；对外错误映射和单次调用次数不变，不添加隐式重试。
旧 Run 的 `AGENT_START_CONNECTION_FAILED` 本身不能确定是网络断连还是客户端运行异常，
新分类也只说明失败分支，不冒充底层根因或供应商用量证据。

### 管理员任务诊断

管理员可通过 `GET /api/v1/admin/diagnostics/tasks` 和
`GET /api/v1/admin/diagnostics/tasks/{taskId}` 读取 PostgreSQL 中已持久化的任务生命周期诊断。
该接口是只读投影：支持按任务、类型、状态、稳定错误码、Trace ID 和创建时间筛选，不会重试任务、
修改积分、补写事件或触发 Provider。诊断结论只依据任务、执行、Outbox/Inbox、积分、资产摄取、
GEO、文章和媒体投稿表中的稳定状态与错误码；OpenTelemetry Collector 或 Trace 数据不可用时，
业务诊断和 Java readiness 仍保持可用。

返回内容仅包含运营排障所需的安全状态、时间线、失败分类、重试建议和数据缺口标记。接口不会返回
用户正文、Prompt、授权上下文、JWT/HMAC、Provider 内部请求 ID、Provider 临时 URL、OSS 地址或
Object Key、SQL 和原始异常。普通用户不能访问该管理接口，也不存在对应的诊断写操作。

管理员还可通过 `GET /api/v1/admin/diagnostics/runs` 和
`GET /api/v1/admin/diagnostics/runs/{runId}` 查看 PostgreSQL 中的 Conversation Run 诊断投影。
列表支持 Run、会话、状态、稳定错误码、Trace ID、时间和分页筛选；详情只返回状态、时间、
脱敏事件序列、Usage 计数、Context 数值预算/来源、关联任务和统一故障决策。它不会读取或返回消息正文、
Prompt、Context Snapshot/Ref、工具参数或输出、Provider 原始响应、媒体地址、凭据和异常正文。

V014 起，新建 Run 在创建事务内保存当前已校验的 32 位 W3C Trace ID，便于与 Java→Python 调用关联；
历史 Run 保持 `null`，并明确报告 `TRACE_ID_NOT_PERSISTED`，不会猜测或回填。诊断始终以 PostgreSQL
业务记录为真相源；Collector 或遥测后端不可用不会影响诊断读取、Run 事务或 readiness，接口也不提供
停止、重试、补写事件等写操作。

### 生成图片/视频资产与 OSS 摄取

资产摄取默认关闭。测试环境显式启用时需要配置以下环境变量，值只进入受限环境文件：

- `ASSET_INGESTION_ENABLED`、`ASSET_INGESTION_WORKER_ENABLED`、`APP_ENVIRONMENT`
- `ASSET_PROVIDER_ALLOWED_HOSTS`、`ASSET_PROVIDER_CONNECT_TIMEOUT`、
  `ASSET_PROVIDER_READ_TIMEOUT`、`ASSET_PROVIDER_TOTAL_TIMEOUT`
- `ASSET_MAX_IMAGE_BYTES`、`ASSET_MAX_VIDEO_BYTES`、`ASSET_PROVIDER_MAX_REDIRECTS`、
  `ASSET_INGESTION_MAX_ATTEMPTS`
- `ASSET_VIDEO_READ_TIMEOUT`、`ASSET_VIDEO_TOTAL_TIMEOUT`、`ASSET_VIDEO_INGESTION_DEADLINE`
- `ASSET_FFPROBE_PATH`、`ASSET_FFPROBE_TIMEOUT`、`ASSET_ALLOWED_VIDEO_CODECS`、
  `ASSET_VIDEO_DURATION_TOLERANCE`
- `ASSET_TEMP_DIRECTORY`、`ASSET_INGESTION_LEASE`、`ASSET_INGESTION_RETRY_DELAY`
- `ASSET_SIGNED_URL_TTL`、`ASSET_TEST_BYPASS_DOWNLOAD_ENABLED`
- `OSS_ENDPOINT`、`OSS_REGION`、`OSS_BUCKET`、`OSS_ACCESS_KEY_ID`、`OSS_ACCESS_KEY_SECRET`
- `ASSET_CONTENT_SAFETY_MODE`

OSS Object Key 由 Java 按 `zhixiaohe/{environment}/users/{userId}/assets/{assetId}/...`
生成，浏览器、Python 和 Provider 都不能指定。上传后通过 HEAD 校验大小、MIME 和 SHA256；确定性
Object Key 和数据库唯一约束保证重试不会产生重复对象或资产。普通浏览器不获得 Object Key，只有
对 READY 资产鉴权后才能获取短期 HTTPS 下载地址。

VIDEO 摄取要求 ISO BMFF `ftyp` 签名和 `video/mp4`，以固定参数列表调用 `ffprobe`（不经过 shell）
校验视频流、H.264 allowlist、宽高、时长和文件大小。Wan 不声明临时 URL 过期时间，Java 使用独立的
`ingestionDeadlineAt` 作为保守摄取期限；它不代表 Provider 对 URL 生命周期的承诺。

仓库目前没有真实内容安全 Provider。决定不做内容审核时，须显式设置
`ASSET_CONTENT_SAFETY_MODE=NOT_REVIEWED`；上传和生成资产记录 `NOT_REVIEWED`，界面明确显示
“未进行内容审核”，不会标为“审核通过”。`ASSET_CONTENT_SAFETY_MODE=REAL` 仍保留为默认值；启用资产摄取
而没有真实 Provider 时启动失败，避免误报审核通过。`ASSET_CONTENT_SAFETY_MODE=TEST_BYPASS` 仅允许在
`APP_ENVIRONMENT=test` 使用，并以 `TEST_BYPASS` 状态审计；它不是“内容安全通过”，生产环境不能
启用该模式。测试环境切换到 `NOT_REVIEWED` 时，可暂时保留原有
`ASSET_TEST_BYPASS_DOWNLOAD_ENABLED=true`，只为既有 `TEST_BYPASS` 资产继续提供下载和复用；
新资产一律记录 `NOT_REVIEWED`，历史审计状态不改写。仓库已有 Quartz 依赖和 GEO 自动计划 Job；
资产轮询仍复用 Outbox/Watchdog 已采用的 Spring `@Scheduled` 和数据库租约恢复机制，没有新建第二套
资产调度框架。

固定 USER 测试账号 `test001` 的积分账户只能由测试资源
`src/test/resources/db/testdata/test001_point_account.sql` 显式初始化。脚本仅接受
`zhixiaohe_test`，只在积分账户不存在时写入测试积分，不修改 ADMIN，也不属于正式 Flyway
迁移或正式产品定价。

## 站内通知闭环

通知中心复用 `business.user_notification`，不随充值模块关闭。图片/视频永久资产入库、文章阶段生成、
GEO 报告保存和媒体投稿批次结算时，在原业务事务内写入一条幂等通知；失败、部分成功和系统终止
采用真实终态，不在 Provider 接受请求或自动重试时提前宣称完成。成功报告/文章可直达结果，其余任务
进入任务详情。通知写入失败会回滚该次事务，由原有任务恢复机制重试，不额外调用付费 Provider。
列表的 `category` 与 `unreadOnly` 过滤在服务端分页前应用；单条/全部已读仍验证账号归属。
本次不补发历史任务消息、不更改邮件/支付/媒体执行开关，也不新增数据库迁移。

## 已有 GEO 专题的单次预授权监测

这是窄入口，不是通用自动执行框架。只支持用户已有专题的一次 `GEO/MONITOR_RUN`；
不自动发现新问题、扩大平台或问题范围，不自动生成/发布文章，也不新增定时计划。

浏览器先在真实 GEO 会话中请求 `geo-monitor-action-preview`。Java 验证专题地区、真实品牌、
问题/平台选择和项目归属，返回冻结的输入版本、完整输入散列及当前报价；缺少资料返回
`WAIT_INPUT`，不把项目名称猜成品牌，也不把缺少地区猜成全国。用户明确保存有限预算和
本会话授权，再发送包含原始 `executionIntent` 的 USER 消息。普通聊天、模型正文和 Python
proposal 均不能授予预算或创建付费任务。一个会话至多有一条 ACTIVE 授权；到期不等于撤销，
需以当前乐观版本更新到期授权，或先明确撤销再新建，不能静默恢复已撤销授权。

预算设置接口为 `GET/PUT /api/v1/execution-budget-policy`；会话下新增 preview、授权列表/
创建/更新/撤销以及指定 Run 的 `geo-monitor-admission` 查询。设置操作是普通 CRUD，不另加
付费幂等键。消息仍复用现有 UUID 幂等受理，指纹包括完整 executionIntent；重放不改变原始
输入或重新启动模型。刷新/重连按原 USER 的 Run 查询准入结果，不能从助手声称“已开始”推断。

Java 保存 durable admission，待原对话 Run 为 COMPLETED 才再次验证有效安装、冻结身份、
未撤销/未过期授权、专题及资料版本、报价和预算。已 FAILED/STOPPED 的 Run 不执行。
正常条件变化以闭集原因码保存 `WAIT_INPUT`，没有付费副作用；未知数据库错误回滚，保留待处理
记录。成功受理复用 TaskControl 的正式 Task、冻结积分、reservation、GEO Run、ledger 和 Outbox。
审计来源为 `EXECUTION_PREAUTH`，草稿状态为 `ADMITTED/admitted_at`，不填 `confirmed_at` 或
消耗用户点击确认凭据。用户手动确认与后台准入竞争时，实际已有任务优先重放，只能创建一次。
已有授权或准入审计的会话（包括已撤销授权、WAIT_INPUT 记录）不能物理删除，返回既有
RESOURCE_IN_USE；不因执行开关关闭而略过检查，不级联删除审计。无此记录的免费会话仍可正常删除。

准入顺序为 admission 行、账号 SHARE 护栏、草稿、有效身份/授权/范围、确认记录、积分账户；
最后才分配会话事件序号。拿到积分账户锁后才读取现有 billingClock 的新时间，再单独查询
该用户上海自然日的 `TASK_SETTLE` 逐条消耗；不是按 taskId 去重。当天已结算消耗加该用户
全部仍冻结积分再加本次报价，必须不超过每日额度；单次上限也必须通过。锁等待跨午夜时，
以锁后的新上海日和到期时间为准。预算策略的首次生效日不能被后续修改重置。

本入口需要 **V048**（三张窄业务表及草稿 ADMITTED 真相约束），并依赖已有付费任务与平台开关。
`ZHIXIAOHE_AUTOMATIC_MONITOR_ENABLED` 默认 false；
`ZHIXIAOHE_MONITOR_BUDGET_EARLIEST_TRUSTED_DATE` 默认未配置，代表没有可用于新日预算的可信
起始日，不能根据旧 ledger 时间猜测，也不能为通过验收临时放宽。该日期必须依据账务时钟
前置修复发布记录，指定一个完整可信上海日；在此之前不启用新预算准入。
`MONITOR_ADMISSION_POLL_DELAY_MS` 默认 1000，每批至多 20 条；关闭自动执行后仍将终态 Run 的
待处理记录明确收束为 WAIT_INPUT，决不扣费。完成定向测试不等于共享环境已迁移、启用或实测通过；
V047→V048 约束、钱包原子性、同 Run/手动竞争、跨午夜和排队安装写锁需由隔离数据库 Gate 验证。

## Java M1 内部通信

- `/internal/v1/health/ready` 是唯一免签内部接口，并直接根据 TCP 来源地址限制为 loopback；不信任可伪造的转发头。
- 其他 `/internal/v1/**` 请求必须携带完整 HMAC 头。服务端读取原始请求体，按原始 path/query 计算签名，默认校验 ±60 秒时间窗；验签成功后才使用 Redis 原子 `SET NX` 登记 Nonce，TTL 默认 120 秒。
- Nonce 只接受规范的 36 字符小写 UUID；格式或大小写错误会在 Redis claim 前以 `401 / INTERNAL_SIGNATURE_INVALID` 拒绝。
- Nonce Redis 不可用时内部请求以 503 失败关闭，不会绕过重放保护。Nonce Key 使用环境前缀并散列 Key ID/Nonce，不把外部输入直接拼入 Redis Key。
- Java 的 Python Internal Client 支持免签 readiness、带 HMAC 的 capabilities 与签名 POST Body、连接/读取超时和安全的不可用状态；loopback 传输固定使用 HTTP/1.1，避免 JDK h2c Upgrade 与 Uvicorn 互操作破坏签名请求；Python 未启动不会阻止 Java 启动，也不会把 Java readiness 标为失败。
- W3C `traceparent` 的 trace ID 会跨内部调用延续，并为出站调用生成新的 span ID。`X-Trace-Id` 继续用于 HTTP 响应关联。
- `dev/test/mock` 环境可显式启用 `/internal/v1/test/reverse-call` 反向调用探针；生产 profile 不注册该 Controller。
- 本机可通过纯只读的 `GET /actuator/info` 查看完整 `gitCommit`、代码能力、测试服务器部署和 M0 Gate 三层状态；该端点不调用 Python，也不登记 HMAC Nonce。该地址随 Java 一起只监听 loopback，不应由 Nginx 对公网转发。

`INTERNAL_HMAC_SECRET` 与 JWT Secret 必须分离且至少 32 字节。仓库只保存不含真实密钥的共同测试向量；密钥只进入受限环境文件。

M1 状态严格分为三层，且默认都不能推断为通过：

- `JAVA_M1_READY` 仅表示代码能力和本地契约测试已就绪，对应 `/actuator/info` 的 `codeCapabilitiesReady`；
- `JAVA_M1_SERVER_READY` 仅表示指定 Commit 已部署到测试服务器，对应 `testServerDeployed`；
- `M0_GATE` 只允许 `NOT_RUN`、`PASS`、`FAIL`，对应 `m0GateStatus`。

`javaM1Ready` 是 `codeCapabilitiesReady` 的兼容别名。未取得完整的部署、HTTPS、登录、限流、HMAC、
中间件和重启恢复证据前，禁止设置 `JAVA_M1_SERVER_READY=true` 或 `M0_GATE=PASS`。

## 测试服务器 Java 常驻部署

测试服务使用 `deploy/systemd/zhixiaohe-java-test.service`，以非 root 的 `zhixiaohe-java` 用户运行，固定绑定 `127.0.0.1:8080`。目录约定为 `/opt/zhixiaohe/java/{repo,releases,current}`，其中 `current` 是指向某个不可变 commit release 的符号链接；受限环境文件固定为 `/etc/zhixiaohe/java-test.env`，归服务账号所有且权限为 `0600`。

部署时从 Git 拉取并检出经过确认的 commit，在服务器构建 JAR，复制到 `releases/<commit>/guanghe-backend.jar` 后原子切换 `current`。部署记录必须保存完整 commit。回滚只切回前一个 release 的 `current` 链接并重启服务；Flyway 迁移只允许向前兼容，不能依靠应用回滚执行 down migration。

Nginx 如后续启用，只能代理公开 `/api/**`；禁止代理 `/internal/**`、`/actuator/**` 或直接暴露 8080。Python 尚未启动时，Java 仍应保持 healthy；Python 可用性使用专门的内部联调检查验证，不由 `/actuator/info` 隐式探测。

## 跨仓库发布稳定性 Gate

修改 Browser/Internal OpenAPI、Worker 上下文或生成类型后，必须在三个相邻仓库存在时执行零费用 Gate：

```powershell
.\scripts\run-cross-repo-stability-gate.ps1 -JdkHome 'D:\JAVA\jdk'
```

该 Gate 验证 Java OpenAPI 自身约束、Python 严格执行上下文消费者和 Web 生成类型。它不替代各仓库全量测试，也不调用真实 Provider。依赖 Python Worker 的 Java 功能开关只能在服务器对应 Worker 通过 Commit/readiness 准入后开启。
