# Guanghe Agent

光禾 AI 的 Python 智能执行服务。它负责有界 Agent 编排、模型/Provider 调用和 AI
长任务 Worker，不负责用户权限、积分、业务终态或 PostgreSQL 写入。

新增智能体先看 [本仓扩展入口](app/agents/README.md)。三仓完整接入指南随 Java 仓库保存在
`docs/architecture/adding-an-agent.md`，不要照旧意图图复制一个新的运行系统。

## 技术基线

- Python 3.12
- FastAPI + Pydantic v2；GEO 和创作对话共用原生 Agent Loop，不依赖 LangGraph/LangChain
- uv + Ruff + Pyright + Pytest
- 版本化只读 Prompt/Agent Profile Bundle
- 同一 DeepSeek Chat Completions Adapter 支持原生 `tools/tool_calls`、思考模式回传、探活、超时/限流映射和用量统计
- 普通 GEO 由模型在有界循环中选择工具、读取观察并回答；复用 Run Manager、Harness、Registry/Policy/Executor 和 Java HMAC 客户端
- 兼容路径保留强制 Tool Calling 意图分类、专用候选参数函数及 GEO/IMAGE/VIDEO/ARTICLE 有界子图，不作为新普通 GEO 对话的前置流程
- Provider 状态区分 `CONFIGURED`、`AVAILABLE` 与 `UNAVAILABLE`
- 有界 Chat Run、稳定 SSE 事件、Stop 与本地 Runtime Harness
- Provider-neutral 长任务契约和全领域 deterministic Provider Mock
- 与 API 分离的 Worker ASGI 进程、RabbitMQ 手动确认传输、Claim-before-ACK、优雅排空和 Worker 控制台
- OpenTelemetry SDK + OTLP/HTTP 的有界 Trace 基础，可由 Collector 可选导出到 Langfuse，
  默认关闭且导出故障不阻塞业务

## 本地运行

```powershell
Set-Location D:\coding-project\guanghe-agent
uv sync
uv run guanghe-agent-api
```

另开一个终端启动独立 Worker（默认使用 local/test Stub + Provider Mock）：

```powershell
Set-Location D:\coding-project\guanghe-agent
uv run guanghe-agent-worker
```

服务默认只监听 loopback：

- 存活检查：<http://127.0.0.1:8000/health>
- 就绪检查：<http://127.0.0.1:8000/internal/v1/health/ready>
- 能力握手：<http://127.0.0.1:8000/internal/v1/capabilities>
- Agent 开发控制台：<http://127.0.0.1:8000/dev/agent>
- 本地 API 文档：<http://127.0.0.1:8000/docs>
- Worker 存活检查：<http://127.0.0.1:8010/health>
- Worker 就绪检查：<http://127.0.0.1:8010/internal/v1/health/ready>
- Worker 开发控制台：<http://127.0.0.1:8010/dev/worker>
- Worker API 文档：<http://127.0.0.1:8010/docs>
- 独立 VIDEO Worker 就绪检查：<http://127.0.0.1:8011/internal/v1/health/ready>
- 独立 GEO Worker 就绪检查：<http://127.0.0.1:8012/internal/v1/health/ready>
- 独立 ARTICLE Worker 就绪检查：<http://127.0.0.1:8013/internal/v1/health/ready>
- 独立 WORKFLOW Worker 就绪检查：<http://127.0.0.1:8014/internal/v1/health/ready>

`/health` 只说明进程存活；是否已加载合法 Bundle 由 readiness 表示，Provider
状态则由 capabilities 单独返回。服务不会因为缺少真实模型密钥而伪报可用。

开发控制台使用最小授权上下文 Stub，覆盖普通回答、`interaction.required`、
`task-draft.proposed`、SSE 增量和 Stop。`MODEL_GATEWAY=AUTO` 仅在配置了 DeepSeek 时
选择 DeepSeek；未配置时只允许 local/test 使用 `LOCAL`。AUTO 不会选择 Qwen，DeepSeek
探活或调用失败后也不会切换 Qwen 或 LOCAL。控制台会直接显示
`MOCK/REAL`、Provider 探活状态、模型名和 Token 用量。控制台生成的任务草稿只是提议，
不报价、不扣积分、不创建正式任务。

创作连续指令先沿用当前 USER 的直接授权；省略对象的短句会复用 Java 返回的最近六条完整原文，由现有 Harness/Gateway 最多做一次结构化意图核验（无自动修复重试，计入原 Run 预算）。窗口内历史缺失、记忆边界变化、否定或对象不明时仍补问。改图和图转视频承接必须使用 Java 唯一冻结的上一张生成图，不能由模型传素材 ID，不能退成纯文生图；Java 准入时重建指纹和来源后继续执行现有余额、并发及幂等校验。

普通 Qwen 图片请求从冻结 `creativeDirection.requestText` 保留用户原话，再附加场景、风格等辅助描述；辅助描述不能覆盖指定文字、字体、排版、保留项和排除项，商品事实及已确认视觉方案的限制仍优先执行。GEO 专用视觉方案和蒙版路由保持原约束。图像参考编辑仍受生成模型能力影响；本地替身测试不证明像素不变，也不验证视频字幕、配音或时间同步。

`agent-bundle-v1.59.0` 复用同一原生循环与文章 TITLE→OUTLINE→BODY：需求宽泛时只补问一个会改变结果的问题，给两三个易懂选项，已有资料不重复索要，不要求用户编写提示词框架。创作聊天中的短文案和润色直接交付文字；保存为可编辑完整文章仍走既有 GEO 文章流程，不扩展创作智能体权限。文章各阶段围绕同一内容目标取舍材料、减少重复与无关分支，保留用户明确篇幅、章节及事实限定；不机械截断、不增加质量审查模型调用。写稿补答需通过既有单次语义核对，普通资料补齐、讨论和裸肯定不会绕过原准入。

`agent-bundle-v1.60.0` 根据本地真实成稿中重复结尾的问题，进一步要求大纲在固定前分清各节信息职责，不为复述已有结论单独增加收尾小节；正文在相关位置完整保留事实限定，收尾不再重复同一原则和注意事项。用户明确要求的章节、总结及重复提醒仍优先，已有长文预算不变。

`agent-bundle-v1.61.0` 将本条消息选定的图片统一视为当前参考图，包括上传图与点击引用的生成图；同一资产出现在历史结果和本条附件中不产生第二个候选。当前仅有一张参考图、用户明确要求修改且未另指历史对象时直接使用 `CURRENT_ATTACHMENTS`。Java/Python既有有源编辑识别同步支持“再添加/加上”及明确对象，不改变资产归属、来源冻结、预算或历史图片的指代检查。

`agent-bundle-v1.62.1` 复用既有一次创作语义核验处理本轮选图后的自由修改需求，不要求用户使用特定动词。Java候选同时绑定当前USER持久附件与Run冻结附件，支持有当前引用但无历史的独立改图；历史图仍遵守完整上下文与唯一来源保护。模型只判断句意及来源模式，不能选择素材ID、扩大授权或绕过后续预算及资产校验。咨询、否定与对象不清不会因有附件而直接创建付费任务。

## Agent 边界

### 诊断报告卡片准备（2026-10-13，M1）

Java 显式持久化的诊断草稿经 HMAC `POST /internal/v1/geo/diagnostic-preparations` 请求四阶段
候选：`BRAND_RESEARCH`、`COMPETITORS`、`DISCOVERY_QUESTIONS`、`REPUTATION_QUESTIONS`。
复用 API 的现有 DeepSeek Gateway、GEO 分析容量、Chat Run Manager 拥有的博查搜索 Adapter 和
Harness。Python 不保存草稿、不扣积分、不创建监测，也不改变普通对话路由。已编辑/手动条目由
Java 保留，Python 只生成剩余 AI 槽位；默认五条发现、三条口碑，合计继续遵守现有十题上限。

每次准备受 Java `deadlineAt` 与本地六十秒期限约束；品牌/竞品阶段最多三种搜索查询、六次
实际搜索 HTTP，文字模型最多两次 HTTP（含至多一次结构修复）。问题阶段使用已确认资料，
不要求额外联网。`synthesisUsage.providerCallCount` 保持文字模型请求计数，响应另外返回
`searchRequestCount`；未知供应商费用不伪装为零，准备遥测不改变现有钱包计价规则。

模型资料投影复用实际消息估算，初始消息不超过 11000，连工具 Schema 与一次格式修复也保持
16000 输入上限。来源按已选竞品引用、本阶段新搜索、其余历史资料排序并限制到 32 条，
返回的来源保留完整原摘要及稳定 ID；只给模型单份有界摘录，引用必须来自其实际可见部分。
竞品准备的模型摘录优先本阶段搜索，既有来源 ID 的标题、URL 和原摘要保持不可变。
资料裁剪返回中文提醒。问题阶段仅使用已确认品牌、竞品和保留的问题，不发送历史网页摘要；
原始用户快照及人工修改仍保持完整，未显示的别名与问题继续参与运行时排除和重复校验。
最小必要资料仍无法装入原预算时返回明确的 422 输入预算错误与中文提示，不重放模型请求。

Prompt 为只读 `GEO_DIAGNOSTIC_PREPARATION_V5`。已有 V1/V2/V3/V4 Bundle 原文件保持只读用于历史溯源。
模型只接收竞品名称/品牌词和保留问题文本，系统管理的 ID/来源标记/编辑状态保持在完整输入校验中。
原生工具 Schema 按阶段固定 null/空数组与候选条数；系统只含平铺阶段元数据，不再放嵌套模板或占位示例。
用户消息只有品牌事实、排除项和来源摘录，不放输出模板。
数量仅指新候选；问题必须补齐剩余槽位，默认五条发现、三条口碑。
未知字段、输入回显、工具/Schema 外壳仍拒绝，不自动拆包装；最多一次原生工具结构修复。
诊断修复仅反馈允许的失败阶段、最多八条脱敏字段/错误类型，不反馈原值、错误正文或原始模型输出。
诊断语义校验使用独立固定原因码，不扩大普通 GEO/article 的反馈白名单。
仅在所有来源、引文、名称、别名及引用集合严格校验通过后，将保留竞品重复推荐作为不追加的 no-op，
不覆盖人工资料，并明确提示未重复添加及可靠新增不足；其他无据候选、自有品牌、批内重复和超额仍拒绝。
完整修复反馈的最坏情况与实际消息使用同一渲染器预留预算，原 16K 请求上限和两次模型调用不变。
返回来源只能来自实际搜索或 Java 输入，模型
只能引用来源 ID 和连续摘录；运行时检查品牌/别名及官网对应来源，不能机械证明网页主张本身为真。
搜索仅含摘要，不冒充官网全文审计；歧义、缺来源或不完整品牌简介交给第二张卡的用户核对。
目标地域沿 Java 提供值，未知保持空值，不从公司地址推断。发现问题排除自有及选中竞品名称；
口碑问题必须点名自有品牌。搜索/模型/期限失败返回稳定错误，不能用固定候选冒充生成成功。

M1 只准备可编辑卡片。监测新分类、后台诊断执行、图文详细报告与 PDF 属后续 M2，不能用本地
候选生成检查宣称诊断报告已交付。浏览器始终经 Java；草稿归属、版本水位、用户改动合并、
幂等与后台任务均由 Java 控制。本地功能检查使用 HTTP/模型替身，不产生真实供应商费用。

### 诊断单次执行与报告解读（2026-10-14，M2）

GEO Worker 新增诊断专用 `DIAGNOSTIC_RUN/STANDARD`，复用原 Rabbit、租约、Provider 单次矩阵和
有限失败补采；成功/失败保存后恢复均不重采。只交接 `geo-diagnostic-sampling-v1` 与 SYSTEM 零模型用量，
全部失败也交接，业务任务仍由 Java 保持 RUNNING 并负责失败范围、结算和独立交付；不把采样完成当 PDF-ready。

签名 `POST /internal/v1/geo/diagnostic-analyses` 当前使用 `GEO_DIAGNOSTIC_ANALYSIS_V11`、既有 Gateway/容量/Harness，
只读 Java 分类指标与真实答案/来源。仅诊断解读使用既有 JSON_OBJECT 通道并注入完整 Schema，
其他只读/准备/采样的工具协议不变。诊断最多两次模型 HTTP、120 秒，V11 的 Schema 与最坏修复消息不超过 24K；
模型投影可裁剪但不改变 Java 全文、指标、手工资料或来源 ID。引用必须在实际展示的连续原文内。
新快照 matchedBrandIds 由 Java 完整回答匹配；旧 V1 缺失/null 标记保留为未知，不更改历史 hash。
模型紧凑矩阵保留每单元全文标记，先缩公开来源再缩采样回答；短摘录没品牌不能推全文没有或信息缺口。
口碑只解释本次 AI 回答，不是消费者评价或市场份额；单次截面不证明优化提升或因果。
全文附录、交付租约、报告重试、PDF 和私有 OSS 都由 Java 处理；本地解读通过不能宣称 PDF 已交付。
V9 将每个并列主张限定到对应回答与地域，全国规模只作背景；模型视图单独使用 `excerptClipped`
表示解读摘录的裁剪，原 wire 的 `answerTruncated`、保存全文与历史报告不变。固定说明改为面向客户的
证据/附录用语，不追加词法拦截、质量评分模型或调用。V10 进一步按 unitKey 分述每条回答的细节，
建议以行动为主，不复述跨组年份/数量；确需复述时逐条归属到同组实际证据。V1–V9 Prompt 保持不可变。
V11 仅诊断解读采用 18K 初始/24K 含 Schema 与最坏修复的输入、16384 输出、最多两次模型 HTTP 和 120 秒上限。单次范围归属与
实际文案质量仍以真实回答逐条复核，离线 Prompt/投影检查不能证明模型必然正确。
详见 [执行与只读解读边界](docs/geo-diagnostic-delivery-v1.md)。

2026-10-23 咨询版：旧六字段与 Java 精确统计摘要保留，新增 `strategy`，包含有据核心判断、
品牌认知观察、实际问题意图分层与建议路线图；每项建议增加现状、业务影响、交付物、验收、
建议责任角色和建议阶段。历史报告缺这些字段可读取，V11 新生成必须完整，不能编造目标增长或
客户承诺。关键词层只引用冻结问题及其同分类成功回答，路线图引用本报告建议零基索引。
Java 提供最多 1600 字连续原文摘录；Python 保留实际来源标题与 URL，先覆盖分类/平台，再逐回答轮询来源，
非空来源集合不会被投影裁成空。极端预算下仍保存完整分类指标与全部样本命中矩阵；仅省略
重复统计文字与有界证据摘录，既有采样、钱包、恢复与调用次数保持不变。
先减少冗余来源，再以50字阶梯缩短回答；仅诊断HTTP读取可等待120秒（剩余总deadline仍生效），
连接/连接池保持短限时，共享客户端及其他模型业务超时不变。正文强调具体认知、行动交付与验证，
不把16384上限当作写满目标，不为匹配参考版式杜撰事实或强凑条数。

### 2026-10-13：模型自主选择文章动作，提交前统一语义核对

复用原生循环、`context.read`、现有一次 `article_action_review` 与 Java 文章准入，不再从
`GeoBehavior.after_tool_batch` 强行执行写稿。`article_generate` 在能力允许时始终可见；上下文读取
不会开关该工具，模型可以继续查资料、回答或提出写稿。固定 TITLE/OUTLINE/BODY 只是已受理的后台
制作配方，不控制对话节奏，不要求固定提议句式或用户重复“文章”等口令。

Java 返回的 `articleContinuation` 是 `authorized=false / reviewRequired=true` 的同项目同聊天原始
消息候选，不是授权。完整 sourceText / offerText 交给既有单次语义核对；当前否定、讨论、局改和
多动作歧义不能升级为付费新稿。“一整版新稿”后接受与其它唯一完整新稿提议同等处理。
只有 NEW_DRAFT 后 Harness 才注入内部 `continuationReview` 三个消息 ID 和 decision，Java重新
读取原证据并消费提议。该字段不进模型工具Schema，模型不能覆盖。无候选的短肯定、异项目、已受理、
结果未知、更新读取撤销及其它写入仍阻止重复/越权生成；EDIT_EXISTING不另建收费新稿。
Prompt Bundle `agent-bundle-v1.55.0`，执行发布 `native-20261013-v1`，原模型、预算、计费和投稿确认不变。

### 2026-10-12：面向客户搜索问题的 GEO 成稿

复用旧系统的媒体品牌稿定位、读者/业务/卖点/语气与关键词组织方式，继续使用现有 TITLE/OUTLINE/BODY、
Gateway、一次有界格式修复及流式预览，不恢复旧经营空间、强制资料表或逐阶段用户确认。
`editorialProfile` 是 Worker 提示内的只读索引，从已冻结品牌/商品快照及有效同项目记忆派生；每项保留
来源，删除和更正优先，ACCOUNT 只提供表达偏好。完整资料仍在原授权上下文，索引最多重复 2400 字字段值，
不截断事实限定词，也不增加 API/结果 Schema。历史、网页和旧稿不自动升格为品牌事实。

候选标题按事实支撑、读者相关性及品牌内容目标排序，第一个是编辑推荐而非固定科普题；大纲围绕单一主张
组织具体已知资料，正文自然结合品牌业务，用相关稳定知识回答真实需求，不编造品牌能力。FAQ、
表格与免责声明不是每篇必备，实际服务范围不能推断门店存在或不存在。现有中立写作、局部改写、投稿确认
与计费边界不变。本次 Bundle 为 `agent-bundle-v1.53.0`，不可变执行发布为 `native-20261012-v5`，Java/Python 精确白名单同步。
文章 Worker 仅加载文章事实边界、编辑策略与当前阶段，不混入聊天的经营咨询目标。自动确认的提纲只固定
结构，不提升段落建议的事实权威；风险写入风险字段，不成为正文写作任务。默认 MEDIUM 约1000～1500字，
显式篇幅和扩充要求优先；品牌事实少不再自动降成两节简介。通用知识必须与企业实绩分开，不为凑字虚构。
Java 可冻结同项目同会话中明确整段确认的来源事实 `confirmedFacts`，即使确认后的模型回复失败；保留
原摘录、完整确认原话及消息归属，不长期回写记忆，不等于独立验证。更正/删除 cutoff 优先；普通“好”不确认事实。
当前用户 `brief` 与 Java 冻结的历史 `writingReference` 在模型输入中物理分离；历史可承接话题和结构，
没有指令或品牌事实权限，避免助手提议的流程被当成企业实绩。完整原需求不截断，不增加模型调用。
选题提议与现有 TITLE→OUTLINE→BODY 按媒体品牌文章编辑工作流组织，文体服从原 USER 与有效偏好，
tone 只表示语气。科普、产品介绍、决策稿各有表达方法，故事或案例须有真实材料，不将所有稿件写成采购指南。
每阶段均读取原要求和有效资料；TITLE 的 rationale 仅解释候选，不声称会传给下一阶段。OUTLINE 从相同要求与
selectedTitle 独立组织材料，在原有 summary/coreMessage/section.brief 中确定 BODY 的主线与段落职责。
助手提议是可纠错草案，不是事实或行业标准；用户只说“四部分”只固定数量，用户亲自明确的章节职责才固定。
OUTLINE 可重编助手论述，保留用户主题、标题、文体、篇幅与节数。公众段落示例同时展示决策与科普写法，
用具体解释、可观察资料与适用条件提供内容，不以内部审稿提醒填充正文。品牌资料只支持相关业务介绍，
不证明达到标准或取得效果，也不强制逐条对标或多处露出。
用户确认不等于公开来源独立核验，行业常识不等于市场趋势或转化证据。正文加粗遵守 CommonMark 标点边界，
不自动改写历史稿件。模型、温度和阶段数量均未改变。
大纲在原有节 `brief` 中分配篇幅，正文在同次生成内压缩重复。运行时只从权威 brief 识别明确且无歧义的
数字字数区间与节数，生成同源 `writingConstraints` 提示数据及逐请求 Schema 校验：OUTLINE 冻结前校验节数，
BODY 校验固定块结构和字数。引用、代码、否定、冲突、历史参考、局部 REWRITE 不产生全文硬约束，
只识别完整命中的最小正向语法（如“全文1200–1500字”“文章分四部分”“以及约1200–1500字的四部分写作结构”），
不从局部段落预算、旧稿描述或资料中的数字猜测要求；其他自然语言要求仍交给现有提示理解。
MEDIUM 不增加硬性默认值。可见非空白字数包含节标题/表格/代码文字，不含主标题、Markdown语法、图片与引用目标；
复用已锁定的 markdown-it-py CommonMark+table 解析。失败只使用既有一次修复和预算，仍失败按原失败路径保留草稿，
不截字、不额外扣费或增加质量评分模型。格式通过不代表可投稿质量，仍须真实全文验收。

质量验收需要阅读真实全文，检查题文一致、品牌主线、事实依据、重复与咨询套话；结构校验及品牌出现只能
证明功能条件，不等于可投稿质量，也不保证媒体审核或 GEO 效果。不额外串联付费模型自评分。

### 2026-10-10：自然写作请求、真实稿件核对与品牌覆盖

普通 GEO 不再以“文章”等关键词决定是否展示写作候选。原生模型提出 `article_generate` 后，
复用同一文字 Gateway，在原 Run 的次数、输入及期限预算内做一次提交前语义核对；只读当前完整
USER 和授权同聊天历史，不接受网页、工具正文或模型自述作为授权。仅明确完整新稿请求进入现有
Java 准入；讨论、否定、局部修改与对象不明不创建新稿。无审核结果时不回退到关键词放行。
简短单篇确认仍用 Java `articleContinuation`，不是多加一轮用户确认；监测、投稿边界不扩大。

Java 在同账号/项目/聊天范围冻结有限写作参考，父 `editorial.requestText` 保持当前原文，
只在 stage brief 内附有来源的参考，当前要求优先。历史不是品牌事实或执行授权；自动参考尊重
记忆更正/删除边界。现有 `context.read.latestArticle` 可返回真实持久正文、版本、状态和截断标记，
用于核对刚写的稿件。旧稿可读不代表允许恢复已删除的事实，不使用流式预览冒充已完成稿件。

文章阶段从冻结品牌身份和有效 PROJECT 记忆派生只读 `brandGrounding`。品牌稿方案与实际正文
必须包含准确品牌名/别名；漏品牌直接复用原有一次 Schema 修复，不新增任务或二次扣费。明确
中立/匿名写作可豁免，不能由引文或非授权历史触发豁免；局部 REWRITE 不越界改其他正文。
ARTICLE 使用专用事实/权限提示层，不加载聊天路由与操作提示；保留原预算和失败边界。
Bundle 为 `agent-bundle-v1.47.0`，Java/Python 文章结果白名单与当前版本一起发布。

### 2026-10-10：原生对话的可见过程与正文流

普通 GEO/创作循环复用 DeepSeek Gateway 的 SSE 传输；每轮先记录 `PLANNING_WORKFLOW`，
收到第一个公开正文片段才进入 `COMPOSING_RESPONSE`。品牌读取、引用资料读取与联网搜索按真实操作
记录既有进度枚举；这些代码所有的阶段不是原始模型推理。`reasoning_content` 仅保留在本轮模型协议中，
不会进入浏览器消息、数据库正文或日志。现有 32 阶段上限不变。

`message.delta` 默认追加，新增可选 `replace: true` 表示替换本条回复，可用空字符串清除临时内容。
原生模型可能先输出说明再选择工具，整批工具校验通过后撤回该临时说明；协议纠错前同样清除，
不把多个模型轮次串成一篇正文。完整工具批次完成并验证前不执行任何工具，不重试已提交的中断流。
流中断或用户停止保留已收到的正文并准确标为未完成；终态不凭客户端推测。

上游完整返回单一 `length` 终态（SSE 还必须有 `[DONE]`）时，仅在本轮尚未执行任何工具、
无已受理任务或未知写入、原调用次数/步骤/期限仍有余量的情况下，允许一次新的模型决策。
此恢复与下述工具协议纠错共用一次额度：先清除残稿，再使用最后完整上下文、同一模型与工具目录，
保持 `max_tokens=4096` 并关闭思考模式；本 Run 后续轮次也保持关闭，避免回填工具协议混用。
不追加截断的正文、推理或工具参数，不修改 Prompt Bundle，不重置预算或执行器去重状态。
缺少 `[DONE]`、断网、中断、非法响应、用户停止、预算耗尽均不走此恢复；已有任务只返回真实回执。

Run Manager 首片立即转发，后续真实片段按 80ms/256 字符合并，减少逐 Token 持久化开销；
末尾、异常与停止前刷出剩余片段。Java 仍是唯一持久化方，Web 按事件水位合并与重放，
需要三端同步发布。文章 Worker 的右侧正文预览、任务准入、积分及投稿确认规则保持原样。

### 2026-10-10：同一聊天中的单篇文章写作确认

**历史设计，已由 2026-10-13 的模型自主选择方案替代。** 以下隐藏工具和批次后确定性承接仅记录旧版行为，
不作为当前接入规则；当前工具始终按能力可见，候选须经模型选择及提交前语义核对，读取不自动写稿。

复用 `context.read` 和既有 `article.generate`，不新增模型参数、客户端、路由或任务类型。
短句“好/认可/就按这个写”自身不开放写工具；模型先读取 Java 的 `articleContinuation`，
Python 核对当前消息、项目、聊天及来源/提议消息引用，只有明确授权且 `NOT_ATTEMPTED` 才开放文章工具。
Java 负责核验最近的单篇写作提议、冻结完整需求和材料、消费提议并进行预算/权限/幂等准入。
合法的 `NOT_ATTEMPTED` 单篇确认在完整工具批次结束后，由循环确定性地经现有工具执行器承接一次，
不再增加模型请求来决定是否提交，也不伪造模型工具调用历史。新的授权读取会撤销旧 pending；
取消、步骤/工具预算与截止时间不变，拒绝、不可用和结果未知均不在本轮重新尝试。
回复使用真实任务回执，受理不等于正文完成；固定回执可单片输出，仍记录真实执行及正文交付进度。
`ALREADY_STARTED` 只读已有稿件状态，不重新生成；已受理回执与结果未知的写入仍沿用现有防重保护。
无提议、普通方案认可、否定、问句、纯标点或多动作不因短确认自动执行；监测和投稿授权不扩大。
明确“按你刚才的方案生成一篇文章”按本轮写作请求处理，不新增确认步骤。
Prompt Bundle 升级到 `agent-bundle-v1.46.4`（`GEO_NATIVE_LOOP_V1` 17），文章 Worker 继续严格拒绝未知版本。
离线功能测试覆盖真实循环的目录刷新、范围校验、否定边界与单次回执；不代表真实模型对话验收。

### 2026-10-10：原生工具回复的有限纠错

原生模型回复的工具批次若包含未开放函数、缺失/重复调用 ID 等协议问题，整批拒绝，
不执行其中任何动作。普通 GEO/创作循环仅在没有已受理任务、没有结果未知的写入且原额度仍有余量时，
让模型重新决策一次；不扩大工具目录，不重放已拒绝批次，原截止时间与调用/步骤/上下文上限继续有效。
已成功读取的结果仍留在当前循环，已受理任务继续按真实回执展示。持续错误仍明确失败，不伪报完成。
稳定错误主码不变；新增有限枚举的拒绝阶段用于诊断，不记录模型返回的工具名、参数或原始回复。
确认官网摘录可以用于当前分析，但不能把旧资料伪装为当前用户原文写入长期记忆，
也不能仅凭助手此前承诺启动新文章任务；明确写作请求和最终投稿确认规则保持不变。

### 客户品牌档案与渐进引导（2026-10-09）

GEO 原生循环复用现有项目公开品牌名、`currentMemory` 与 `brand_save` / `memory_upsert`，
不新增业务表、API、Harness 或强制表单。模型上下文的只读 `brandBrief` 投影统一四项：对外品牌名、
主营业务（`brand.categories`）、实际经营/服务范围（`brand.service_area`）、长期默认目标曝光地域
（`geo.target_regions`）。后三项是 PROJECT/FACT；可选的长期推广重点 `geo.priority_offering` 和
需求方向 `geo.target_queries` 是 PROJECT/PREFERENCE。项目标签不得替代公开品牌名。

新用户每轮最多补问一到两个关键问题；已有项目记忆、当前用户原话、报告或相关资料足够时不重复问。
投影的未记录字段不构成全局执行门槛，旧同义字段仍可被模型读取，不自动迁移/复制事实。
实际服务范围与目标曝光地域不能混用；本周、本月或一次活动范围只留在当前聊天，不覆盖长期默认。
未知回答、智能体建议、外部搜索结果不自动沉淀成品牌事实；记忆仍需本轮 USER 原文及当前版本，删除标记不恢复。
用户为已有主营补充品类时，`memory_upsert` 可传 `mode=APPEND`，只提交本轮新增原文；Java 合并旧值，
不要求模型拼接旧用户消息或覆盖既有品类。该模式只限未删除的 PROJECT/`brand.categories`，
纠正、完整替换和首次写入仍用默认 `REPLACE`，其他字段不开放追加。

明确命名请求或紧接品牌名称补问的简短当轮回答，可以进入既有 Java 品牌保存校验；品牌回执在当前
循环更新公开名称投影，随后读取最新项目记忆，不复用开头旧名称。普通补资料和认可优化方案不会开放监测/文章
创建工具；信息足够时给方案。明确监测/写文请求，或经 Java 核准的单篇写作提议确认才允许对应动作。报告分析与已明确的写文请求
按各自必要资料处理，不重新要求用户收齐全部品牌字段。最终投稿确认不变。

离线功能测试验证真实循环与工具协议、原文写入、当轮投影刷新、项目隔离和动作拒绝；不以脚本模型
的固定回答冒充真实对话质量验收。部署及少量真实对话由联合发布 Gate 单独记录。

智能体平台首批执行身份由 Java 的可选 `agentIdentity` 授权快照冻结，不在 Chat Run 请求中接收。
进程内注册来源为 `app/agents/runtime-catalog.json`，当前包含 `geo/creative/summary@1.0.0`。
GEO 与创作复用 `NativeAgentLoop`，GEO 品牌投影与文章承接在 `app/agents/geo.py`；摘要仅总结
当前输入文字，复用文字网关与预算/Stop，但没有 GEO 工具或任务提案。旧 `GeoAgentLoop` 名称只作兼容包装。
摘要优先保留原文支持的独立信息；不足用户要求的条数时可以少写，不重复或添加内容凑数。
未提供身份的旧 Java 按 `geo@1.0.0` 兼容解析，仍遵循下面的入口分流；未知身份、版本或缺少必要能力
明确失败，不选首个 Profile 降级。每个 Run 只加载一次初始 Java 授权 Context；后续工具读取最新业务
资料仍由 Java 逐次验权，不缓存跨 Run 授权。能力在既有工具执行前取交集；身份只写脱敏 Trace 和用量
日志，不记录用户名称、项目名称、正文或 Prompt。

已有专题的单次监测仅在 Java 为本轮原始 USER 请求冻结 `monitorActionContext` 时，确定性地进入
既有 `ExplicitActionAdapter` 的 `task_draft.propose` 工具。显式 `articlePreparationMode=ARTICLE_FULL_V1`
同样保留既有准入协议，不经过旧 Commerce Graph。这两个入口不切到普通 GEO 的原生循环。单次监测分支沿用 Registry、
Policy、能力交集和工具预算；不调用路由/参数模型，
问题、平台、采样模式与地区原样保留，提案只有既有六个 MONITOR_RUN 参数，不附加 `requestText` 或
`agentSummary`。旧 pending、资料或自然语言不能恢复此显式信号；普通对话中的新监测请求改由下述
原生工具处理，不再要求先经过旧 QUESTION_DISCOVERY 草稿准备对话。兼容动作上下文不是授权或扣费
凭据，Python 只提交提案，不声称已受理/执行；正式准入仍由 Java
独立检查身份、权限、价格、输入版本与积分。当前附件、工作流历史快照不继承这个单次动作。

Python 负责有界 Agent Loop、固定动作适配器、模型/Provider 网关和 AI Worker。
Java 仍然负责身份与权限、积分、价格、PostgreSQL、正式草稿/任务状态以及最终结果
验收。兼容路径的工具注册表开放受控 QUERY/ANALYZE/PREPARE 能力；复杂多交付目标通过
`workflow_plan.propose@1` 复用 Java Internal HMAC 客户端持久化待确认计划和独立报价草稿。
所有模型可调用工具的真实执行均经过 Registry、Policy 和 Executor。`COMMIT` 默认禁止；本次仅在
原生 GEO 的显式 Java 动作模式中允许白名单 `brand.save`、`geo.monitor`、`article.generate`、`memory.upsert`、
`memory.forget`，且必须是 `JAVA_PROXY`、
`JAVA_REQUIRED`。这不授予 Python 直接写库、决定价格/积分或对外发布的能力。
运行态分别统计执行步骤、真实文字模型请求、Java Context 读取、工具执行和外部
Provider 请求，旧的 `steps/modelCalls/toolCalls` 字段继续作为兼容镜像。

### M1：普通 GEO 原生对话

入口为 `app/agent/loop.py` 的 `NativeAgentLoop`，提示词为 `GEO_NATIVE_LOOP_V1`。普通 `geo@1.0.0` 对话不先调用
Intent Router、不进入 LangGraph：模型通过同一 Gateway 返回原生函数调用，工具执行结果以匹配的
`tool_call_id` 回填，再由模型决定继续调用、补问或回答。当前轮的调用、结果与供应商要求的
`reasoning_content` 完整保留，不向用户或日志输出原始思考内容。

用户从聊天“＋”选中报告后，Java 提供 `selectionSource=EXPLICIT_REPORT` 的逐 Run 授权快照。
原生对话优先分析这一份报告及其时间范围，不以最新报告、旧会话或其他读取结果替换；“分析这份报告”
只读取已有结果，不自动发起监测。显式且可用、范围一致的快照可直接支持统计答复；`geoContextUsed`
让 Java 为回复附上已选报告的引用，表示该快照作为输入可用，不冒充资源工具调用或完整原文已读。
需要原回答时仍经既有资源工具读取同一报告；返回另一份报告会被拒绝，不将错配证据交给模型。

当前 GEO 工具目录包含以下十项；是否实际开放还取决于 Java 授权能力与 `loadedToolGroups`：

| 模型工具 | 用途与边界 |
| --- | --- |
| `context_read` | 经 Java 读取当前品牌、相关知识原文、可用平台、预算和近期任务，不持续轮询。 |
| `brand_save` | 经 Java 保存本轮用户明确提供的品牌名称/别名；不再把整条 USER 反复追加为项目知识文档。 |
| `memory_read` | 读取当前授权项目的最新事实、表达偏好和删除标记，以及明确设为全部项目通用的账号表达偏好；不猜客户、不缓存旧快照。 |
| `memory_upsert` | 保存或替换本轮 USER 明确提供的单项事实/长期表达偏好；范围、原文来源、当前版本和持久化均由 Java 校验。 |
| `memory_forget` | 删除本轮 USER 明确要求忘记的条目；墓碑保留，不从旧会话恢复。 |
| `geo_monitor` | 用户明确要求执行监测时请求 Java 验权、核价和正式准入；泛化优化请求只给方案。排队回执不是完成报告，同一轮不重复创建任务。 |
| `article_generate` | 用户明确要完整文章时直接请求 Java 复用全文生成链路；原 USER、授权项目与显式报告不由模型传入。自动完成标题/结构/正文，不再要求生成前确认；投稿仍须最终确认。 |
| `resources_search` | 查找授权范围内的空间、商品或 GEO 专题，返回服务器签发的候选引用，不发起监测。 |
| `resources_read` | 使用候选/观察引用读取已有报告、原回答证据或资料，不编造引用或重新采样。 |
| `web_search` | 复用现有博查 HTTP Adapter 查询公开网页摘录，返回真实来源链接；不创建监测、不抓取全文、不自动写入品牌记忆。 |

联网工具只在 Java 授权 `web.search`、清单允许工具且发放供应商调用预算时开放。
旧 `geo@1.0.0` 定义的 `geo.read` 仅通过清单显式 legacy 映射兼容，不是新 Agent 的通用扩权规则。
默认最多 3 个不同查询、共 6 次 HTTP 尝试，受原有截止时间、工具和供应商预算约束；同一查询成功或失败
后换调用 ID 不再重发。每次最多 8 个有效来源，页面指令只是非可信资料。没有搜索结果不代表品牌不存在。
用户要求联网写文时，Harness 将本轮真实来源摘录注入内部 `article.generate`，由 Java 冻结到文章上下文的
`untrustedResearch`；模型不能伪造该参数，不覆盖品牌事实或长期记忆。文章 Worker 同样以非可信资料使用它。
搜索进度使用 `SEARCHING_WEB`，失败不降级成监测，不虚构成功或来源。
检索由模型按必要信息缺口自主决定，不要求用户说“联网”。搜索观察回填后，模型核对对象、地区、
时间和事实是否足以回答；不足时在同一预算内改写查询继续调用，依赖前次发现的查询分轮完成。
已有资料足够则直接回答，不强制搜满三次；上限/截止时间内仍未核实的事实明确保留未知。
工具成功只表示取得摘录，不表示信息已足够，也不等于已读取网页全文。
每次搜索观察的 `data.searchBudget` 给出当前 Run 已用查询、剩余新查询和 HTTP 尝试额度；
包括缓存命中也重新投影当前余量，不把缓存时的旧余量当作新额度。模型后续同批调用不得超出余量，
实际执行仍受原 Harness 截止时间与全部调用预算校验，并不由模型自行扩大额度。
原生业务动作另有本轮 USER 拒绝边界：仅查资料、要方案或分析报告时不开放监测/文章创建工具，
网页、历史记录与模型参数不能扩大这一动作集合。此边界不替代 Java 的身份、项目、预算与幂等校验，
也不影响用户从固定入口明确提交的动作；明确联网写文仍直接生成，无新增确认步骤。
监测授权独立于旧领域路由快捷规则，自然前缀（如“那跑一次监测吧”）不再被误拒绝。
短句“提交”仅在 `context_read` 返回同项目、紧邻原 USER 的未尝试监测证据时放开工具，
不从摘要、模型建议、模糊“再跑一次”或更早指令推断授权；已受理/结果未知不重复创建。
`budget.configured=false` 仅表示没有自定义额度，`policySource=DEFAULT/status=ENABLED` 的默认额度有效；
实际报价、余额与每日额度仍由 Java 准入检查。没有调用监测工具不能宣称“提交失败”。

这条路径复用既有授权、预算、Stop、Trace 和 SSE；`run.progress` 表示实际执行阶段，M1 在最终模型
响应完整返回后交付正文，不把内部思考流当用户回答。真实 HTTP 尝试、工具调用和总步骤均受原有预算
约束。写调用超时或响应损坏时保留“结果未知”，本轮不换编号/参数再次提交；已拿到监测或全文生成回执后，后续
解释失败不会丢弃已受理状态。后台执行、失败恢复和业务终态继续由 Java 与既有 Worker 处理。

项目知识原文召回仍保留；另接入 Java 权威 `knowledgeContext.memory`，按账号与当前授权项目隔离
事实和长期表达偏好，同项目跨会话可用。模型不传用户/项目编号；只有本轮 USER 明确设为所有项目
通用的表达偏好可存为 `ACCOUNT`，客户事实只能是 `PROJECT`，不保存性格猜测、网页或竞品描述。
记忆使用稳定单字段 key、版本和 USER 原文片段；不是对话摘要。更正与删除返回最新快照及
`historyCutoffAt`，原生循环随即重装系统数据、移除早于该时间的历史及无版本摘要，不拆当前轮的
原生调用/结果配对。墓碑优先于旧聊天和资料，不能自动复活。已发文数量仍从结构化业务记录读取。
未选项目时只可读取明确的账号表达偏好，不能默认把客户资料提升为全局。此阶段不含代理机构成员
授权或跨账号客户共享；旧偏好 UI 不代表自动迁入新记忆。上下文保留 Java 已限界的资料、历史与引用；
原生循环通过签名 `compaction/source` 读取持久水位之后的原始历史，而非只拿已经裁剪的最近消息。
未压缩历史超过 `min(16000,max(4000,maxInputTokens/3))` 字符时，分批经现有 DeepSeek Gateway
生成接续摘要，再由 Java `compaction/checkpoint` 以版本、原文 hash 和最新记忆 revision 校验后写入
`conversation.summary/summary_version`。历史消息不删除，下一 Run 从已提交水位继续；当前 USER、
独立任务/报告/待授权快照不被摘要替代。模型预算不足则保留已提交进度并提示继续，不宣称任务完成。
输入达到 75% 预算会优先压缩剩余历史/完整旧工具轮，最新原生工具调用与结果仍保持配对。
更正/删除 fence 使旧摘要失效；同一轮记忆 revision 变化也会重读校验，不能复活旧品牌事实。
单个不可拆的大块输入或压缩后仍超预算会明确暂停，不丢弃原聊天记录或无限调用模型。

普通“帮我进行 GEO 优化/提高可见度”只读当前资料与已有报告，先交付方案，不自动创建监测或文章付费任务。
明确“根据这份报告生成文章”通过 `article_generate` 直接创建完整稿件，不以标题建议或聊天草稿代替。
Java `article.generate` 复用现有 `ARTICLE_FULL_V1` 准入、预算与 ARTICLE Worker；模型只能传可选
`tone`、`targetLength`，不能传用户、项目、报告、价格或授权编号。动作 `COMPLETED` 表示受理，
正文是否完成以回执 `data.status` 为准；同轮更换参数或调用编号不再生成第二篇。Java 按真实回执附文章
结果入口，既有结果页提供编辑、媒体推荐及最后一次投稿确认。Python 不保存文章、不替用户发布。
“根据报告生成文章”默认生成面向顾客的品牌稿件：报告辅助选题，品牌事实与文风取当前项目授权资料和有效记忆，
不将监测复盘或 GEO 运营教程当作品牌成稿。只有用户明确指定报告解读或科普文体时才采用该方向。
无基线时说明可分析的范围；当前没有“监测完成后自动续跑生文”，不作续办或优化效果承诺。
创作智能体的生图/视频工具按独立身份与能力开放，不向 GEO 目录混入。媒体下单、发布或定时计划
未开放为 GEO 原生模型工具；既有独立功能和
显式 `MONITOR_RUN` / `ARTICLE_FULL_V1` 入口由 `ExplicitActionAdapter` 直接处理 Java 已冻结的动作，
不运行意图路由或图框架。普通对话统一由模型选择原生工具。旧提案调用的纯 Python 兼容函数仍保留，
但所有 StateGraph/CompiledStateGraph 执行与 LangGraph/LangChain 依赖均已删除；它们不是生产对话备用路由。
当前为实现与真实功能验收进行中，不能以代码检查、旧 Router 评测或单次模型响应宣称整条链路已验收。

普通 AI 对话的正式主模型为 DeepSeek。Qwen Adapter 保留，但 AUTO 和显式 QWEN 均不会
将其用于文字对话；Qwen Image 与 Wan Video 分别通过独立 Adapter 接入 IMAGE/VIDEO
Worker。媒体能力不改变 DeepSeek 文字主模型，临时 Provider URL 也不等于资产持久化。
M1 先行基线已包含双向 HMAC、Redis Nonce 和 Java 反向探针，Java 权威 Run Context
已经接入。Worker 本地模式使用进程内消息队列和 Java Control Plane Stub，但严格执行
“先 Claim、后 ACK”；本轮新增的 RabbitMQ 传输复用同一 Worker Runtime，支持被动订阅
Java 已创建的 IMAGE/VIDEO/GEO/ARTICLE 队列、QoS、手动 ACK/NACK/reject 和有界关闭，不自行声明 Exchange、Queue
或 DLQ。

Rabbit v1 消息 Schema 已放在
`contracts/rabbit/task-execution-requested-v1.schema.json`，本轮不修改该共享 Schema。Java
Internal OpenAPI 已冻结 IMAGE/VIDEO/GEO/ARTICLE 的 Claim、Execution Context、Heartbeat、Execution
Event，以及媒体 Result Artifact、GEO Observation/Completion、ARTICLE Content Result 契约；`WORKER_CONTROL_PLANE=JAVA`
复用现有 HMAC Java 客户端装配真实控制面。Rabbit 模式按独立进程只被动消费
`zhixiaohe.agent.image`、`zhixiaohe.agent.video`、`zhixiaohe.agent.geo` 或
`zhixiaohe.agent.article`，连接或 Java 暂时不可用时
fail closed，且不会回退到本地 Stub。Claim 阶段的临时 Java 故障会 NACK/requeue 并停止接单，
Java 恢复后需由服务管理器重启 Worker 再继续消费。代码就绪不等于 Java/Python 任务闭环已经联调完成。

## Trace 基础

`app.observability.tracing` 是 API 与所有 Worker 的唯一 OTel 装配入口。有效的入站
`traceparent` 会成为真实父上下文；Chat、Rabbit、Java Handoff、DeepSeek、工具及 Provider
边界只记录稳定状态、预算、Token、重试次数和不可逆 ID 引用，不记录正文、Prompt、密钥、
Authorization、Context Ref、完整媒体 URL 或原始异常。Rabbit 重投递创建新 Trace，并以 Link
关联原消息上下文，避免将跨执行恢复伪装成一个持续 Span。

默认 `GUANGHE_AGENT_OTEL_ENABLED=false`。启用时必须同时配置无凭据的 OTLP/HTTP Endpoint；
缺少配置保持 NOOP，Collector 失败由 SDK 有界批处理异步消化，不影响业务 readiness。测试环境
使用 100% 采样；production 默认采用 10% ParentBased head sampling，可通过受控配置显式调整。
head sampling 不能保证保留全部错误 Trace；若后续要求错误全留，必须在 Collector 增加并验证
tail sampling，而不能把当前能力描述成已具备。Collector、Langfuse 可选导出、脱敏边界和受限
本地文件输出的部署说明位于 [`deploy/README.md`](deploy/README.md)，具体字段映射见
[`docs/langfuse-otel-export.md`](docs/langfuse-otel-export.md)。不使用 Langfuse SDK 或 LangSmith。

同一装配入口可独立启用低基数 OTel Metrics；Collector 在 loopback `127.0.0.1:9464`
暴露 Prometheus scrape，不影响业务 readiness。指标边界、标签白名单、进程重启语义与告警
模板见 [`docs/python-agent-metrics.md`](docs/python-agent-metrics.md)。仓库不安装
Prometheus/Grafana，没有外部服务时其状态应报告为 `PENDING`。

IMAGE Worker 可显式选择 deterministic Mock 或 `QWEN_IMAGE/qwen-image-3.0`。REAL 模式启动时
必须通过 Qwen 探活，且不会静默降级到 Mock；调用前还会校验 Java 授权的图片数量（1～4 张）。Qwen
没有返回权威费用时上报“未报告”，不能把兼容值解释为免费。Python 仍不写 PostgreSQL。

## 配置

### 客户项目周期报告文字解读

Java 显式创建的周期报告通过签名 `POST /internal/v1/geo/period-report-summaries` 请求文字解读。
输入仅为冻结的 `jobId/inputHash/deadlineAt/evidence` 和可选 `title/focus`；证据最多 48 KiB、
深度 8、每集合 128 项、单字符串 6000 字。逐 `topics[].platforms[]` 检查 `comparisonStatus`、
共同有效样本和数值证据，只允许对应可比平台作定性解释；不得汇总为项目整体提升或因果效果。
此入口共用现有 DeepSeek Gateway、GEO 并发信号量和 Harness，包含传输重试/结构修复在内最多
两次真实请求、总计最多 60 秒；不经过 Agent 路由、不调用业务工具、不写数据库、不采样或投稿。
只读版本化 Prompt 为 `GEO_PERIOD_REPORT_SUMMARY_V1`，返回最多 6000 字的定性 Markdown 与
实际 `synthesisUsage`；统计指标、图表、日期和链接由 Java 确定性生成，不由模型编写。
无数据、不可比和部分失败不能被写成提升，文章/品牌资料里的指令不具备执行权限。
失败返回稳定错误码，不用兜底文案冒充成功，Java 持久任务负责幂等与恢复。

配置仅从受控环境变量读取，变量以 `GUANGHE_AGENT_` 开头。参考
[`.env.example`](.env.example)，不要提交真实密钥。嵌套 Provider 配置使用双下划线：

```text
GUANGHE_AGENT_DEEPSEEK__CREDENTIAL
GUANGHE_AGENT_DEEPSEEK__MODEL
GUANGHE_AGENT_DEEPSEEK__ENDPOINT
GUANGHE_AGENT_QWEN__CREDENTIAL
GUANGHE_AGENT_QWEN__MODEL
GUANGHE_AGENT_QWEN__ENDPOINT
GUANGHE_AGENT_QWEN_IMAGE__CREDENTIAL
GUANGHE_AGENT_QWEN_IMAGE__MODEL
GUANGHE_AGENT_QWEN_IMAGE__ENDPOINT
GUANGHE_AGENT_WAN_VIDEO__CREDENTIAL
GUANGHE_AGENT_WAN_VIDEO__MODEL
GUANGHE_AGENT_MODEL_GATEWAY
GUANGHE_AGENT_INTENT_ROUTER_MODE
GUANGHE_AGENT_INTENT_ROUTER_QWEN__CREDENTIAL
GUANGHE_AGENT_INTENT_ROUTER_QWEN__MODEL
GUANGHE_AGENT_INTENT_ROUTER_QWEN__ENDPOINT
GUANGHE_AGENT_OTEL_ENABLED
GUANGHE_AGENT_OTEL_OTLP_ENDPOINT
GUANGHE_AGENT_OTEL_SAMPLE_RATIO
GUANGHE_AGENT_OTEL_METRICS_ENABLED
GUANGHE_AGENT_OTEL_METRICS_OTLP_ENDPOINT
GUANGHE_AGENT_OTEL_METRICS_EXPORT_INTERVAL_MS
GUANGHE_AGENT_LANGFUSE_EXPORT_ENABLED
GUANGHE_AGENT_LANGFUSE_EXPORT_COLLECTOR_CONFIGURED
GUANGHE_AGENT_RUN_CONTEXT_PROVIDER
GUANGHE_AGENT_WORKER_CONSUMER_NAME
GUANGHE_AGENT_WORKER_BROKER
GUANGHE_AGENT_WORKER_CONTROL_PLANE
GUANGHE_AGENT_WORKER_PROVIDER
GUANGHE_AGENT_RABBITMQ_URL
GUANGHE_AGENT_RABBITMQ_QUEUE_NAME
GUANGHE_AGENT_RABBITMQ_ROUTING_KEY
GUANGHE_AGENT_RABBITMQ_PREFETCH_COUNT
GUANGHE_AGENT_RABBITMQ_CONNECTION_TIMEOUT_SECONDS
GUANGHE_AGENT_PROVIDER_MOCK__FAILURE_MODE
```

`RABBITMQ_URL` 可能包含凭据，只能写入被 Git 忽略的本地环境文件或服务器受控环境文件；
`.env.example` 保持空值。配置 `WORKER_BROKER=RABBITMQ` 时必须同时使用 Java Worker
Control Plane；IMAGE/VIDEO/GEO/ARTICLE/WORKFLOW Worker 分别只能绑定 Java 拥有的
`zhixiaohe.agent.image`/`zhixiaohe.agent.video`/`zhixiaohe.agent.geo`/
`zhixiaohe.agent.article`/`zhixiaohe.agent.workflow`，不能混用或接收任意队列名。
GEO/ARTICLE/WORKFLOW Worker 还必须分别显式配置路由身份
`agent.geo`/`agent.article`/`agent.workflow`。

`RUN_CONTEXT_PROVIDER=AUTO` 在 local/test 使用显式本地 Stub，在 production 使用 Java；
联合测试服务器显式使用 `ENVIRONMENT=test`、`RUN_CONTEXT_PROVIDER=JAVA` 和
`MODEL_GATEWAY=DEEPSEEK`，同时验证 Java 权威 Run Context 与真实文字网关。local 中显式
选择 `JAVA`、或 production 中显式选择 `LOCAL`，都会保持未就绪，不会跨越环境边界或
回退到 Stub。

Agent 主模型配置为 `MODEL_GATEWAY=DEEPSEEK`。模型名称必须来自 DeepSeek 当前
`GET /models` 的实际返回，Endpoint 使用 `https://api.deepseek.com`。启动探活还会确认
配置模型在列表中；只有验证成功才会标记为 `AVAILABLE`，且失败不会降级到其他网关。
真实密钥只放在被 Git 忽略的 `.env.local` 或服务器受控环境文件中，不要写入
`.env.example`、源码或日志。

Prompt Bundle 默认位于
`app/prompts/bundles/commerce-assistant-v1.yaml`。Bundle 缺失或 Schema
非法时，进程仍可存活，但 readiness 必须返回 `503 NOT_READY`。
当前代码版本为 `agent-bundle-v1.46.1`（部署版本以服务器实际运行为准）；`GEO_NATIVE_LOOP_V1` 版本为 `14`，
仅用于上面的普通 GEO 原生入口，旧 Profile 与领域 Prompt 继续服务兼容路径和 Worker。

### 旧 Graph / Router 兼容实现说明

以下记录保留的旧执行路径与历史验证，不描述普通 GEO 原生入口的调用顺序或验收状态。
原 `agent-bundle-v1.33.13` 普通回答使用独立的
`generalResponsePromptCode`，不加载原商务顾问、工具决策和结论先行风格层；仍复用原文字网关、授权
上下文、事实范围与预算。咨询只解释方法和证据边界，不把观察当原因、前后变化当效果，也不依赖历史
助手结论诊断。配置 `generalResponseReviewPromptCode` 时，真实普通回答先缓冲候选，再经原网关最多
一次结构化事实范围审查；严格 ACCEPT 才交付原文，否则交付安全的范围说明，不循环改写。审查可从
必填闭集 `answerTopic` 区分普通请求、品类范围、来源证据、未提及与采集失败、效果边界；来源证据问题即使
审查返回 ACCEPT，也交付程序预写的证据阶梯指导，不放行自由候选（SCOPED_GUIDANCE/accepted=false）。
其他主题拒绝后可交付对应的有限概念指导，
不返回自由改写、不复用被拒候选；缺失或非法选择仍安全降级。通用警告完全未答可安全解释的核心概念
也会被拒绝。拒绝兜底仍记录为 REJECTED/accepted=false，不计作原答通过；本地协议测试不能证明真实语义质量。
普通翻译优先遵循本轮明确目标语言，或同一任务中近期用户原话确认的目标；新目标覆盖旧目标。默认中文
不是翻译目标，目标缺失时只作一句澄清，不回显原文冒充完成。单次复核同时检查最低任务完成或必要澄清，
不以原文与译文相等判错（合法同形专名仍可接受）。GENERAL 的 QUESTION_NOT_ADDRESSED 拒绝使用任务
中性兜底，不注入 GEO 建议；来源证据问题仍优先 SCOPED_GUIDANCE。离线多轮测试只验证上下文、装配与
交付协议，真实翻译质量仍需单独验收；没有新增模型调用、工具、重试或预算。
1.33.8 三轮真实回归中，目标语言缺失的第一轮仍直接给出英文，语义判为 NEEDS_FIX；随后明确英文与
翻回中文两轮通过。技术/Trace 通过不覆盖这个遗留，也不代表翻译目标澄清已可靠实现。1.33.9 只新增
单次监测的确定性提案分支，不修改这条普通回答/复核链或增加重试。
候选和审查
共享原 Run 的请求数、输入和截止时间预算，不扩充预算；用量计入审查请求，无法查到费用仍为未知。
停止或截止不会转成成功，未审查的候选不进入用户流。LOCAL 测试显式标记 MOCK_UNREVIEWED，不冒充
质量验收。模型接受也不代表事实核验，仍需真实多轮语义审阅并记录拒绝/降级比例。
生成事实范围和审查 envelope 复用程序生成的 `evidenceScope`，明确本轮 GENERAL 没有
独立抓取/核验网页或真实平台检索证据；用户、资料、历史和候选文字中的链接或同名字段不能覆盖它。
展示引用只是待核验的文字；独立核验网页只支持网页内容。可核验且绑定平台/本次请求/来源的原生结构化
记录只证明本次采样返回的检索结果，原始回答的引用标注只证明该回答的标注；两者均不自动证明实际采用。
实际使用需匹配主张的额外证据，不能由链接存在、内容对应、检索返回或引用标注替代，更不证明训练、收录
或未来推荐。有效回答未提及和采集失败分开，失败不计为零可见度。此范围不新增调用或改变 Java 授权。
旧 Profile 未配置字段时保留原装配，配置了未知引用则拒绝加载。分类与任务仍按固定顺序加载身份、真实性、决策、顾问、工具
和风格层；GEO、IMAGE、VIDEO、ARTICLE Playbook 仅在当前意图
需要时加载。运行时工具说明来自 Bundle 白名单、本地 Registry 与 Java
`loadedToolGroups` 的交集，Java 授权 Context 始终位于明确的数据边界中并最后追加。
GEO 草稿的问题数上限也来自同一份 Java 授权 Context，由 Java 按平台数和 Provider 调用预算推导；
Python 只负责在该容量内生成与规范化问题，不再假定固定的 10 个问题。
GEO 解读同一次模型调用还提取最多 6 个竞品候选，仅携带连续原文和样本引用；运行态排除无效样本、
来源列表、URL/HTML 属性、自有监测名及常见平台泛词，丢弃不可信候选不触发额外重试。
候选不是正式竞品，入库、去重、商家确认及同批可见度仍由 Java 决定；旧报告和确定性降级不冒充已完成发现。
聊天逐 Run 可接收 `geoInsightContext`：仅包含已验权的保存报告、30 天自然发现范围的最近可比观察、
缓存竞品证据和固定站内链接。旧 Java 未提供该字段时保持未知；未选范围、多专题、无报告和加载失败
分别说明，不能当作零可见度。路由只接收有界状态摘要，已存 GEO 查询复用 `GEO+ANSWER/ANALYZE/PLAN`，
进入专属事实呈现路径；即使是复杂查询，也不进入普通自由综合。只有 `GEO+PREPARE` 才准备新监测草稿。
读取不会重采集、重新分析、确认/忽略候选或修改计划；明确新监测仍走既有草稿报价流程。竞品确认等
变更引导商家在已授权工作台执行，普通“好的/就这样吧”不产生业务副作用。
已有报告查询只读可见度、比较原因、竞品提及/引文和登记状态，不推导竞品胜因、学习方案或自动扩题。
概念解释和起步方法咨询走普通回答，不因提到“可见度”就搜索报告，也不要求先绑定商品；具体报告引用、
实际表现查询和含明确执行请求的混合句保留原有授权数据/任务路径，不将缺失数据编成回答。
模型第二次调用只选择本轮事实目录中的引用，不生成数据、结论、链接或业务动作；引用和范围验证通过后，
程序生成商家语言，再发送 SSE。不可比原因来自趋势原因码，不根据失败样本猜测；目录确认不等于自动跟踪。
普通答复/普通多步综合不注入本轮 GEO 快照；分类失败且绑定报告时安全引导页面。普通历史仍保留，歧义问题
不保证全部自动识别，必要时引导查看报告。原授权 DTO/Trace 不变，数字与引文不做正则删改。
商品可见度入口优先读取当轮 Java 已授权报告；明确分析当前商品且没有报告、没有已有专题时，
才准备 QUESTION_DISCOVERY 草稿。问题按商品类目与使用场景中立拟定，平台只从当前可用清单推荐最多两个；
草稿可修改，范围、调用预算与报价仍由 Java 复核，用户确认前不运行监测。已有专题却没有报告时引导继续配置，
不重复创建专题；范围缺失、加载失败、概念解释、取消或普通“好的”不会触发该首次准备入口。
明确要求基于当前报告写文章时，仅绑定当轮显式选择、验权且允许创作的报告；商家紧接上一条
已保存的 GEO 答复说“那帮我写文章”等口语续接时，也只在该答复所用报告与本轮显式选择的报告
完全一致、商品和经营空间未切换时继承报告。自动最新报告不能冒充上轮来源；缺报告或切换后
先请商家重新选定报告，本轮不建稿。普通写作、明确不按报告写以及解释性提问不自动携带报告，
修订保留前稿来源。关联报告只创建待确认的标题草稿，不扣积分、不开始生成或投稿。
专属 GEO 只读答复完成事件可带 `geoContextUsed: true`，Java 再用自己的冻结快照生成报告入口，Python 不下发可信报告 ID。
Intent Router V2.2 同时输出主意图、决策、复杂度、所需上下文、有界澄清原因和 NEW/REVISE 草稿操作。
关键词只在语义明确为 PREPARE 后帮助拆分多个成果，不能绕过路由、覆盖 ANSWER/CLARIFY，旧任务不能将当前咨询或否定升级成准备草稿。参数结构失败会明确失败且不创建草稿，
不会用默认数量替换用户要求；图片数量与执行面统一为 1～4 张。复杂只读分析才进入有界
3～5 步 Planner；目录、记忆、任务工作区、竞品比较和证据聚合全部经过同一个
Tool Registry/Policy/Executor，且只读取当前 Run 已授权快照。`MEDIA` 目前只支持咨询、分析、
规划或补问，不能准备任务。两个及以上明确付费交付物可形成 Java 权威的 proposal-only
Workflow Plan；只读咨询仍走原 P1 路径，任何确认、扣积分、Provider 执行与 COMMIT 仍由 Java 完成。
Java 持久化的 `taskWorkspace.pendingInteraction` 是跨轮补问与选择的唯一续接锚点；Python
只解析该结构中的 `choices/missingFields/intent`。普通 assistant 文本不拥有流程状态，也不得
用编号选项、隐式草稿或“回复确认继续”制造无法被 Java 恢复的隐藏工作流。

Router 使用严格、确定性裁剪的授权 Context envelope。默认
`INTENT_ROUTER_MODE=DEEPSEEK_PRIMARY`；`QWEN_SHADOW` 只做脱敏指标对照且不改变主结果，
`QWEN_PRIMARY` 必须人工显式切换，失败时不会自动回退 DeepSeek 或 LOCAL。百炼路由使用独立
Credential/Model/Endpoint，不复用 Qwen Image、Wan Video、GEO 或旧文字 Qwen 配置。完整
Provider 模式与原有输入边界见 [`docs/intent-router-v2.1-contract.md`](docs/intent-router-v2.1-contract.md)，
V2.2 增量及草稿修订边界见 [`docs/intent-router-v2.2-draft-revision.md`](docs/intent-router-v2.2-draft-revision.md)。

Router 输入 envelope 与 Trace 升为 `intent-router-v2.3`，仅新增 GEO 证据可用性摘要；
模型输出 `IntentRoute` 仍为 `intent-router-v2.2`，字段和受控路由语义不变。
历史报告的 `summary/findings` 标记为 `ALL_REPORT_QUESTIONS`，只用结构化平台字段回答
`DISCOVERY` 自然发现可见度，不把全文解读的总样本数用作该指标分母。

## 本地质量检查

```powershell
uv sync
uv run ruff check .
uv run ruff format --check .
uv run pyright
uv run pytest
git diff --check
```

Java Internal OpenAPI 或 Worker 执行上下文变更后，还必须从相邻 Java 仓库执行严格消费者对照：

```powershell
uv run python scripts/verify_java_internal_contract.py `
  ..\guanghe-backend\src\main\resources\contracts\openapi\java-internal-openapi.yaml
```

服务器启用依赖 Worker 的 Java 功能前，先在当前 Python Release 中检查运行态和 Commit。例如：

```bash
uv run python scripts/check_runtime_readiness.py \
  --component image --component video --expected-commit <完整 Python Commit>
```

只有 `PYTHON_RUNTIME_RELEASE_GATE=PASS` 才允许开启对应 Java 能力。检查失败不会调用 Provider，只输出稳定 Reason Code。

## Agent 评测

默认入口运行实际 `ChatRunManager → NativeAgentLoop` 功能旅程，包括声明式第四 Agent、
流式回复、停止保留残文、并发能力隔离、无资料权限时不加载品牌/附件、执行版本错配拒绝。
模型与 Java 返回使用离线确定性替身，不访问真实 Provider、不创建业务任务，**不是文案质量或真实模型验收**。
默认 native 路径遇到 `--mode live` 会明确拒绝，不会把 Mock 结果冒充真实调用。

```powershell
uv run python -m evals.run_agent_evals
```

注册入口只有 `app/agents/runtime-catalog.json`，Java 使用完整镜像；工具必须同时满足登记的
allowlist 和完整 capability 要求。Run 绑定 `executionRelease + catalogHash`；只有明确的历史
内置身份允许缺失此元数据，新快照不恢复已撤销的旧能力。GEO 品牌投影/文章承接位于
`app/agents/geo.py`，普通 native 循环不按 Agent 名称分支。Worker 通过清单的 `workerProfileCode`
选择 Prompt，不使用列表首元素。新 Agent 先走固定功能旅程，再按授权做真实对话验收。

下面的 Commerce 意图图评测属于**历史兼容入口**，必须显式选择 `--runtime legacy`，
不能作为普通原生 GEO/创作对话通过验收的证据。其本地回归集包含 36 条合成用例：

```powershell
uv run python -m evals.run_agent_evals --runtime legacy --mode local --suite regression `
  --report evals/reports/local-commerce-v1.json
```

历史入口的真实模型验收默认只运行 4 条 Smoke 用例，避免意外消耗完整评测额度：

```powershell
uv run --env-file .env.local python -m evals.run_agent_evals --runtime legacy --mode live --provider deepseek `
  --suite smoke --report evals/reports/live-deepseek-smoke.json
```

真实完整回归必须显式增加 `--allow-full-live`。评测报告只记录用例 ID、分类、公开错误码、
延迟和 Token 汇总，不保存密钥、原始 Provider 响应或思维链。

意图分类另有 275 条无隐私合成表达的显式付费质量 Gate（包括翻译目标澄清与更正的三轮输入），用于比较 JSON Output 与普通
（非 strict）强制 Tool Calling。没有 `--execute` 时命令会拒绝读取配置或调用模型：

```powershell
uv run python -m evals.run_intent_quality_gate
uv run python -m evals.run_intent_quality_gate --execute --modes both `
  --report evals/reports/intent-quality-v1.json
```

历史分类入口采用 Tool Calling；分类参数仍经过本地枚举 Schema 校验和一次有界修复，不能借此
注册或执行 COMMIT 工具。领域任务参数复用同一有界结构化重试框架，以专用
`propose_task_parameters` 函数输出候选；函数按领域列出参数白名单，本地领域 Schema 仍负责最终校验。
结构错误最多修复一次，截断或未知响应不会被当作草稿成功；日志只保留模式、长度、函数数量和脱敏校验路径。

Prompt V2 另有 36 条高质量 Journey Eval，覆盖顾问分析、跨能力计划、补问、任务准备、
否定表达、指代和越权边界。真实运行会产生 DeepSeek 费用，必须显式确认完整 live 评测：

```powershell
uv run --env-file .env.local python -m evals.run_agent_evals --runtime legacy --mode live `
  --suite journey --allow-full-live --report evals/reports/live-commerce-journey-v2.json
```

报告只保存 case ID、决策、稳定错误码和 Usage，不保存消息正文、Prompt、经营 Context 或
Provider 原始响应。

## Worker Mock 验收

Worker 控制台可以分别投递 IMAGE、VIDEO、GEO、ARTICLE 合成任务。Mock 输出始终带
`mock: true`，GEO 文案明确声明不是实际平台样本。可以通过
`GUANGHE_AGENT_PROVIDER_MOCK__FAILURE_MODE` 切换 `RATE_LIMIT`、`UPSTREAM_5XX`、
`TIMEOUT`、`AUTH`、`INVALID_RESPONSE`、`SAFETY_REJECTED` 或 `UNKNOWN_SUBMIT`，验证
错误分类以及未知提交不盲目重试。这个本地状态不是 Java 正式任务状态。

Python 拥有的内部 API 契约位于 `contracts/agent-openapi.yaml`，Chat Run
事件契约位于 `contracts/events/`。付费长任务不会通过 HTTP execute 接口启动；
后续由 RabbitMQ Worker 执行，并在 Claim 成功后才 ACK。

`GUANGHE_AGENT_BUSINESS_OUTCOME_ENABLED` 默认关闭。Python 在 Run 受理时冻结此开关，
并且必须同时收到 Java 冻结的 `protocolFeatures.businessOutcome=true`，才在普通完成事件
中输出可选 `businessOutcome`。旧上下文缺该字段时仍发送旧协议；STOPPED 不补业务成功。
业务结果来自执行器的实际交互、工具观察及稳定失败原因，模型不能填写。Python 的任务草稿
仍为待验证提议，只有 Java 已保存有效草稿才可将结果标记为 DRAFT_READY。

## Worker 媒体 Provider 验收

### 聊天商品图视觉方案

`agent-bundle-v1.23.0` 在聊天 IMAGE 的原 `suggest_task_parameters` 同次结构化调用中生成
`parameters.visualBrief`（`commerce-image-v1`），不新增模型调用：将平台、用途、视觉焦点、
构图、背景、光线和文字策略转为有界字段，确认前由 Java 一起冻结，Worker 只消费该冻结版本。
新聊天缺失方案会用既有一次结构化修复，仍失败则不创建草稿；修订继承同一草稿未修改字段，
新建不得继承旧商品的方向。用户禁字清除旧文案，新增文案只能来自本轮明确加字请求，不能把
否定提及当授权；价格、销量、优惠、赠品与资质不由视觉方案编造。

Qwen 同次请求保留已授权参考图、原数量/画幅/否定约束，去除重复的创意 JSON；商品身份和
用户明确排除项优先于吸睛设计。旧草稿与独立创作页没有 `visualBrief` 时兼容原行为；本期
智能视觉策划只覆盖聊天商品图入口，不代表已为独立创作页或视频增加策划，也不承诺真实成图
必然提高点击率。Trace 仅记录方案版本、平台、用途与文字策略枚举，不记录文案/Prompt。

### 独立创作页来源与蒙版重绘

创作来源只认 Java 冻结的 `ProductCreativeBrief.sourceMode`，不从缺失图片猜测模式：

| 来源 | IMAGE | VIDEO |
| --- | --- | --- |
| `TEXT_ONLY` | Qwen 纯文本，无参考图和商品事实 | Wan 纯文本，不发送 `media` |
| `SELECTED_ASSETS` | 本次选择 1～3 张资产，无商品绑定 | 本次选择 1 张首帧 |
| `CATALOG_PRODUCT` / `CURRENT_ATTACHMENTS` | 保留现有商品 / 聊天附件语义 | 保留现有单首帧语义 |
| `PRODUCT_MATERIALS` | 不允许 | 商品绑定 1～3 张素材，发送 `reference_image`，不是多图首帧 |
| `GEO_REPORT` | 不允许 | Java 验权报告摘要 + 商品事实 + 1 张首帧；洞察不能冒充商品事实 |
| `REGION_EDIT` | 独立 Wan2.1 蒙版 API，底图 1 张 + 独立受控蒙版 | 不允许 |

`REGION_EDIT` 必须显式配置 `GUANGHE_AGENT_WAN_IMAGE_EDIT__ENABLED=true`、独立
`CREDENTIAL`，固定 `MODEL=wanx2.1-imageedit` 和北京 `ENDPOINT`（见 `.env.example`）。
默认关闭；不会借用 Qwen 凭据，也不会把普通图片任务隐式切到 Wan。IMAGE Worker 复用已有
Router 按 Java 授权 `provider/model/sourceMode` 逐任务分流，普通任务仍为 Qwen Image。

蒙版请求使用 `function=description_edit_with_mask`，白色修改、黑色保留；底图与 PNG 蒙版
同尺寸，边长 512～4096、各不超过 10 MiB，提示词最多 800 字且不截断、不拼接通用长提示。
蒙版以 `controlledMaskAsset` 单独传入，`parameters.maskAssetId`、brief 中 ID 必须一致。
一次只提交一次，`n` 为 Java 授权的 1～4；真实响应 `usage.image_count` 必须与实际结果一致。
该 API 未报告尺寸、MIME、字节数和散列，Python 保持 `null`，不假装输出必然是 PNG。
Java 负责下载识别、验证、按原图和二值蒙版合成，并验证未涂抹像素保持不变。
提交结果未知时沿用原 Provider continuation，只 GET 原任务，绝不因模式切换重投。
REGION_EDIT 的每次 POST/GET 都计入 Java 冻结的 HTTP 调用预算；预算耗尽上报 UNKNOWN
并保留原任务标识，下一轮授权恢复只能查询。普通 Qwen 仍保留既有单次生成提交预算口径。

独立蒙版 Provider Gate 默认拒绝运行且不读取凭据：

```powershell
uv run python -m app.providers.wan_image_edit_gate
# 仅获明确单张真实费用授权后，在 test / MOCK 隔离环境执行：
uv run python -m app.providers.wan_image_edit_gate --execute --request-file <controlled-request.json> --receipt-file <outside-repo-absolute-receipt.json>
uv run python -m app.providers.wan_image_edit_gate --resume --request-file <same-controlled-request.json> --receipt-file <same-receipt.json>
```

输入是 `TaskProviderRequest`，固定 `REGION_EDIT/count=1`，必须包含实际底图和蒙版的受控
元数据与临时 HTTPS URL。凭据只来自独立配置。收据在 POST 前持久化，终态也保留以阻止
重复执行；若提交不明且没有任务 ID，要求人工核对，不允许删除收据后重试。收据不含 Prompt、
签名 URL 或凭据。Gate 只证明供应商响应及恢复协议，明确不宣称资产摄取或像素保护已验收；
完整功能仍需 Java/Worker/浏览器联合验收。本次开发仅运行进程内 MockTransport 测试，无真实费用。

官方依据：[Wan2.1 局部重绘](https://help.aliyun.com/zh/model-studio/wanx-image-edit-api-reference)、
[Wan3 文本 / 参考素材视频](https://help.aliyun.com/zh/model-studio/wan3-video-generation-api-reference)
（核验于 2026-09-22）。执行 Prompt Bundle 升级至 `1.16.0`，聊天流程未扩展。

Qwen Image 的远程 Gate 默认拒绝执行且不会读取凭据。只有在受控测试环境显式配置
`ENVIRONMENT=test`、`MODEL_GATEWAY=DEEPSEEK`、Qwen Image Credential/Model/Endpoint，
并确认本次单张图片费用后，才可增加 `--execute`：

```powershell
uv run python -m app.providers.qwen_image_gate
uv run python -m app.providers.qwen_image_gate --execute --reference-image-url <short-lived-https-url>
```

Gate 固定使用 `qwen-image-3.0`、1 张、1K、1:1，只输出脱敏的请求/任务标识及 PNG
尺寸、字节数、SHA256，不输出临时签名 URL；下载文件在检查结束后删除。Gate 成功只证明
Provider 级生成可用，不代表图片已进入资产中心或付费任务链路。

Wan Video 的独立远程 Gate 同样默认拒绝执行。它固定使用北京地域
`wan3.0-video`、1 个 2 秒 480P 16:9 无声视频；先执行不产生视频的查询探活，再只提交
一次异步生成任务，后续仅 GET 轮询，并使用 `ffprobe` 核验下载后的 MP4。首次执行必须
指定仓库外的绝对状态文件路径；提交成功后会在首次轮询前以 `0600` 权限原子保存完整
恢复标识：

```powershell
uv run python -m app.providers.wan_video_gate
uv run python -m app.providers.wan_video_gate --execute --reference-image-url <short-lived-https-url> --state-file <absolute-state-path> --ffprobe <ffprobe-path>
uv run python -m app.providers.wan_video_gate --resume-from <absolute-state-path> --ffprobe <ffprobe-path>
```

Gate 只输出脱敏 Provider 标识、轮询次数、容器/编码、尺寸、时长、字节数和 SHA256，
临时视频会在检查后删除。若提交已确认但结果不确定，只允许用原 taskId 恢复查询，禁止
再次 POST。`--resume-from` 严格使用状态文件内固定的 Provider、模型、Endpoint 身份和任务
标识，不能由命令行混搭覆盖；UNKNOWN 或进程中断时保留同一状态文件，SUCCEEDED、FAILED
或 CANCELED 确定终态后安全删除。状态文件不含 Credential、Prompt 或临时视频 URL，仍须
放在受控临时目录且不得提交 Git。Provider Gate 与正式 VIDEO Worker 是独立验收；正式
Worker 只把临时 URL 移交 Java 做永久摄取，不在 Python 下载后上传 OSS。

在受控环境配置 Qwen Image 后，将 `GUANGHE_AGENT_WORKER_PROVIDER` 设为 `REAL`、
`GUANGHE_AGENT_WORKER_TASK_TYPE` 设为 `IMAGE`、`GUANGHE_AGENT_WORKER_CONTROL_PLANE`
设为 `JAVA`，Worker 才会装配真实 `QWEN_IMAGE/qwen-image-3.0`。启动探活失败即
`NOT_READY`，不会回退到 Mock。Java 授权 Context 的 `maxImages` 与请求数量必须完全一致，范围为 1～4 张。
一次任务仍只提交一次 Qwen 请求，以 `n` 指定候选数量。真实结果允许返回 1～请求数量张图片；
少量结果必须有与实际图片数量一致的 `output_image_count`，空结果、超额结果、重复 URL
或不一致的计量均会被拒绝。Python 只上报真实结果，不补造缺失候选，Java 按成功永久入库
的资产数量决定积分结算与余量退款。原单张结果兼容未报告图片计量的响应；费用未报告仍保留未知。
官方参数及响应依据：[Qwen Image 3.0 API](https://help.aliyun.com/zh/model-studio/qwen-image-generation-and-editing-api-reference)
（核验于 2026-09-22；供应商 `n` 支持 1～6，业务 Worker 授权上限为 4）。
此处部分结果接收是控制面的防御性兼容，不把供应商文档解释为部分成功的保证。

本地隔离调试仍可显式使用 `IMAGE_VIDEO`，但 Java 正式执行必须使用相互隔离的单类型
IMAGE/VIDEO Worker：

```powershell
uv run --env-file .env.local guanghe-agent-worker
```

访问 `http://127.0.0.1:8010/dev/worker` 可投递 IMAGE/VIDEO 本地验收任务并预览临时结果。
真实请求可能计费；默认图片规格为 `1K`，默认视频规格为 `720P`。Qwen Image 临时 URL
按供应商规则短期有效；Wan 响应未显式返回过期时间时不伪造 `expiresAt`。正式链路必须由
Java/对象存储及时转存。Worker 文章生成尚未接入真实 TaskProvider；GEO 使用独立的平台注册表、
有界采样执行器和 DeepSeek 综合器，真实模式不会回退到 Mock。

REAL Worker 的临时结果会携带 Provider 请求/任务标识、过期时间、尺寸和“费用未报告”
语义后回报 Java。完整 URL 及签名参数不得进入日志；代码就绪、Provider Gate 与服务器
RabbitMQ→Java 闭环 Gate 是三个独立结论，未完成联合 Gate 前不得宣称正式媒体链路可用。
已确认提交但轮询结果不确定时，Worker 以 `UNKNOWN` 和完整 Qwen continuation 标识回报
Java；后续 `resumeOnly=true` 的 execution 只查询原 `providerTaskId`，绝不再次提交生成。
恢复成功沿用现有结果 DTO，明确失败按终态失败回报，再次不确定则保留原标识继续由 Java
执行有界恢复。

UNKNOWN 失败允许缺少请求或任务回执，仍保留真实供应商和模型身份。没有完整的可查询
回执时，Java 必须终止本次执行并释放剩余冻结，不得派发重投或恢复查询。Python 不伪造
回执，不接受无完整回执的 resumeOnly Context；有效恢复执行只查询原任务。

## GEO Provider Prep

GEO 执行基础位于 `app/geo`，固定平台注册表只接受 `DOUBAO`、`DEEPSEEK`、`QWEN`、
`WENXIN`、`KIMI`、`YUANBAO`、`ZHIPU`。一次查找只会命中请求的平台；配置缺失、鉴权、
网络、限流、维护或引用缺失都会保留为该平台的真实状态，不跨平台降级，也不补模板答案。
Provider 回答和引用始终是不可信数据，DeepSeek 只在严格输出边界后做分析，不改写原始样本。

正式 GEO Worker 复用通用 Rabbit Consumer、Claim-before-ACK、Java HMAC Control Plane 和
Worker 生命周期。它按 Java 授权的问题、平台和采样轮次生成确定性 unit key，成功与永久失败
Observation 立即幂等上报 Java；首轮临时失败按下述有界补采策略得到最终结果后才上报。
恢复执行只以 Java 已持久化 Observation 为真相并跳过已有 unit。所有 unit 保存后才以
DeepSeek 做一次结构化综合，Java 决定最终状态和积分。独立能力
Gate 仍默认拒绝执行，只能在受控 `test` 环境显式运行：

```powershell
uv run python -m app.geo.gate
uv run python -m app.geo.gate --execute
```

Gate 对每个平台最多创建一个无隐私测试样本；Kimi 使用 Moonshot 官方 Search Pro
先取得带来源的检索结果，再由 Kimi Chat 生成回答，成功样本产生两次 HTTP 请求。
中国区账号使用 `https://api.moonshot.cn/v1/chat/completions` 与同站 Search Pro；
国际区账号使用 `https://api.moonshot.ai/v1/chat/completions` 与同站 Search Pro。
两区 API Key 不互通，搜索与回答始终跟随同一个已配置 Chat Host，鉴权失败不会跨区重试。
旧内置 `$web_search` 不再使用。DeepSeek 样本固定调用官方
`POST https://api.deepseek.com/v1/tools/search` 获取最多 8 条来源，再复用同一个 DeepSeek
凭据与当前配置模型，通过现有 Chat Completions 文字网关生成基于来源的回答；该监测链路不再
调用博查，也不依赖博查凭据。官方搜索失败沿用有界重试，搜索无有效来源时明确失败，不以
无搜索依据的直连模型回答补位。输出仅包含
能力状态、回答哈希/长度、引用域名、Token、调用次数和延迟，不包含问题正文、完整回答、
密钥、Authorization 或上游原始响应。真实费用只有供应商返回可验证金额
时才记录，否则保持未知。

各平台配置使用 `GUANGHE_AGENT_GEO_<PLATFORM>__*` 嵌套变量，完整占位符见
`.env.example`。腾讯运行链路使用独立 `GEO_TENCENT_SEARCH` 凭据的 SearchPro（Query/Mode=0），
只解析官方 Response.Pages 的结构化 URL/摘要，再交给 TokenHub hy3（thinking disabled）回答。
缺搜索凭据、HTTP 200 内嵌 Error 或无有效来源均失败，不降级为普通模型回答。
每样本最多搜索一次和回答一次；采集方式记录 `tencent-searchpro-then-hy3-v1`，不称为元宝客户端或内置搜索表现。
旧内置搜索诊断适配器不参与运行态装配，要求结构化来源与 search call 计数同时非空才成功。
两段式的 search_requests=1 是独立 SearchPro 调用，不伪造 TokenHub web_search_call。
配置占位符见
`.env.example`。Qwen 联网调用允许使用官方北京通用 Chat Completions Endpoint，也允许迁移到
Key 所属北京 Workspace 的专属 Host；OpenAI 兼容协议不返回结构化引用时必须明确标记为
`DIRECT_SEARCH_NO_STRUCTURED_CITATIONS`，不得伪造 citation。

### 仅重生成 GEO 解读（2026-09-28）

`POST /internal/v1/geo/syntheses` 仅供 Java 经现有 HMAC 调用，输入必须是已保存的
问题快照、监测对象与回答；不启动采集、搜索或新监测。Java 负责报告归属、幂等作业、
每份报告最多两次恢复和结果落库。Python 在既有 GEO 分析容量内执行，最迟为
`deadlineAt` 或接收后 60 秒（取较早者），最多两次模型 HTTP 请求，含最多一次结构修复。
结构/超时/供应商失败返回非 2xx，不以 `geo-deterministic-v1` 冒充恢复成功。

请求带 `evidenceProjection` 说明回答节选和引用省略；原始证据不改写。Python 再使用既有
每回答 1200 字符、三条引用的有界分析投影，并告诉模型此边界。七字段分析 Schema 通过
强制 `synthesize_geo_report` tool-call 传给 DeepSeek；`missingSamples` 和 `methodVersion`
由运行时生成。修复保留原系统约束，日志只留安全字段路径、错误类型、稳定码和用量，
不回显证据/Prompt。接口返回真实模型 Token 和请求次数，不能表述为不产生模型费用。
`visibilityEvidence` 必须携带 Java 全文名称匹配的每单元结果，节选缺名不得推翻全文提及。
普通监测在所有单元保存后，再通过既有签名 Observation 读取取得同口径的全文核验投影，
校验完整单元矩阵、失败/不可识别的 null 和冻结目标后，才交给解读；不在 Python 重建名称匹配。
兼容调用缺少该投影时，模型只能描述所见节选，不能推断完整回答未提及或可见度为零。
每次解读同时携带全平台注册名称与确定统计的 `canonicalFacts`，同一八字段模型输出 Schema
约束平台身份、已核验状态和数量/比例的一致性。身份错配、否认可核验结果或确定数值冲突复用
既有一次有界修复；仍矛盾则失败，不替换错误文字冒充正确解读。首次汇总可沿既有确定性降级
保留原证据和真实全文指标；仅重生成接口仍返回明确失败，不把降级当成智能解读成功。
护栏覆盖明确的身份、状态和数值矛盾，不宣称能机械证明所有自由文字结论。

GEO 解读输出经过本地商家文案投影：已知问题编号和内部字段改成可读称呼，商品型号、金额与引用链接保留；
不会因展示格式增加模型重试。零提及或监测对象不明时，在摘要给出名称核对、查看证据、同范围下次监测的下一步，
不把这些核查动作伪装成文章/图片创作建议，也不依据平台超时编造营销缺口。
新采样的 `collectionMethod` 由实际适配器记录版本，成功和失败记录都保留；博查后 DeepSeek、Kimi Search Pro、
各平台原生搜索分别记录，Mock 明确为 `mock-v1`。历史未记录字段保持 `null`，不回填、不重采样。

### GEO P0 执行策略（2026-09-24）

豆包独立采用 connect/pool/write/read = 5/5/10/120 秒，单次请求总时限 150 秒，
且不能超过 Java 下发的任务剩余时间。已有显式 `GEO_DOUBAO__TIMEOUT_SECONDS` 配置仍优先；
新增策略变量位于 `.env.example` 的 `GEO_DOUBAO_POLICY` 段，其他平台原调用方式不变。
每步最多两次请求；临时失败的豆包重试等待 15–30 秒抖动，遵守 `Retry-After`，超过
任务余量或最大等待时间则不提前重试。鉴权、参数等永久错误不重试，不关闭联网搜索或换平台。

复用现有 Worker，以 Java 授权的最多 210 个单元分轮次等待额度；先取豆包平台额度，
再取任务额度，退避期间释放两者。豆包默认并发 2 **只约束当前 Worker 进程**，不是共享账号 QPS。
综合和结构修复也受剩余调用数及任务截止时间约束；无额度或时间时保留采样，交付明确标记的
`geo-deterministic-v1` 报告。租约丢失时停止，不交付降级报告。诊断日志仅包含阶段、耗时、
尝试次数、状态码和稳定错误码；超时/网络错误标记上游结果未知，不记录正文或密钥。

此阶段不增加数据库表、API 字段或调用次数上限。跨进程/旧系统共用账号的配额、
出站前持久化预算和“发送后尚未上报即崩溃”的未知费用仍属 P1；失败综合的外部用量也未形成
完整账本，降级报告的 SYSTEM/0 仅表示基础统计自身不用模型，不代表整段综合没有发生费用。
发布时必须核对 GEO 专用 Worker 的实际进程目录、版本和生效配置，不能只看 API/current 链接。

### GEO 失败单元有界补采（2026-10-09）

首次执行先完成全部首轮采样，再仅对已明确分类的网络、超时、429、5xx 临时故障补采一轮；
成功单元、鉴权/参数/配置/格式错误和无法确定原因的模型失败不补采，也不重新创建任务。
补采复用同一 provider-call 预算、截止时间、并发额度和执行租约，不增加任务预算。
HTTP 层已有的每步最多两次请求仍计入同一预算；搜索加模型的每次实际调用均计数。
429/5xx 最后一次响应的 `Retry-After` 通过进程内私有元数据保留，超过原截止时间或
Provider 最大等待时间时不提前重试；补采等待不占执行额度，租约丢失立即取消。
页面阶段为 `GEO_RETRYING`；完成数只统计最终上报的唯一单元，不把一次补采计为两个结果。

最终 Observation 累加本次执行内两轮的实际调用数及已知 Token；失败请求未返回的 Token
和上游金额保持未知，不假造完整成本。失败样本仍为失败/缺测，不作为零提及；用户积分仍由
Java 按成功单元结算，不按重试次数重复收费，上游真实成本与用户积分不混为一谈。
原有“已发请求但尚未上报即崩溃”的未知调用窗口仍存在，本次未增加出站前持久账本。
因此恢复执行（`executionNo > 1`）仅续做尚未持久化单元，不再叠加补采轮次；所有已持久化
成功或失败单元仍跳过。本轮不提供对既有终态历史任务重新收费补采的接口。

## ARTICLE Worker

ARTICLE 有界生成内核位于 `app/article`，只接受严格的 `TITLE → OUTLINE → BODY → REWRITE`
阶段请求；其中 `OUTLINE` 是兼容 mode 名，实际产物为正文前的结构化“文章创作方案”。旧独立分步
入口保留用户确认；完整文章入口由 Java 自动采用标题和方案，不让用户逐阶段确认。
它复用同一 DeepSeek ModelGateway、版本化 Prompt Bundle、ARTICLE Playbook、结构化修复重试、
Usage、Trace 和 Harness 预算边界；每次只加载当前阶段指令。`TITLE` 固定输出 5 个去重候选；
`OUTLINE` 输出摘要、核心观点、目标读者、段落结构、风格、篇幅、发布平台和风险提示，同时保留
`outline` 兼容镜像。`BODY` 和 `REWRITE` 必须同时服从 Java 已确认的标题、完整 `confirmedPlan`
与一致的 `confirmedOutline` 镜像；`REWRITE` 只合并 Java 授权的正文块。素材和 GEO 报告只能以
Java 提供的授权摘要进入模型，Python 不按 ID 读取数据，也不编造销量、排名、资质或引用。
生成快照内的 `knowledge.memory` 延续项目隔离及有效值/删除标记语义，不从旧资料恢复已删除事实。

ARTICLE 输入快照与 `ARTICLE_CONTENT` artifact 均使用 schema v2。单次 Worker 成功只表示当前
生成阶段成功；标题/方案确认、重新生成的业务版本、积分和整体文章状态都由 Java 决定。ARTICLE
Worker 复用既有 Rabbit Consumer、Worker Runtime、Java Internal Client、HMAC、Trace
和 DeepSeek ModelGateway，被动消费 Java 拥有的 `zhixiaohe.agent.article`（routing key
`agent.article`）。它严格校验 Java 授权的单阶段 Context，并将 `ARTICLE_CONTENT` 结果回报 Java；
Python 不保存、确认或发布文章，不处理积分。独立进程仅监听回环地址 `127.0.0.1:8013`。

BODY 在同一次 DeepSeek JSON 生成请求中使用真实 SSE；收到的 `blocks[].markdown` 逐段解码，
通过既有 HMAC `TASK_PROGRESS/ARTICLE_BODY_STREAMING` 发送有界累计预览，每秒最多两次。
预览含模型尝试号和 execution 内单调递增序号；原有结构修复开始时先清空上一尝试，
不会为了预览再请求一次模型，也不会在网络断流后重放生成。Java 独立保存并鉴权提供预览；
前端只渲染安全文本/Markdown，不能把预览当作正式正文、资产或可投稿内容。
只有完整响应通过原 Schema、标题/段落一致性校验及原结果回执后才产生正式成稿。
预览传输暂时不可用时停止预览上报，既有心跳与最终结果仍继续；执行授权/租约被拒绝则停止。
本地检查不能证明页面真实流式体验，仍需部署后真实生文验收。TITLE/OUTLINE、计费与发布确认不变。

每次 ARTICLE HTTP 调用（包括传输重试和结构化修复）都在请求发出前检查实际 messages 的输入
Token 估算上限；修复保留原有阶段/事实边界，不回灌失败回答全文。总体超时不会抹掉已尝试调用数
或已返回的 Token 用量。请求发出后的 `ARTICLE_DEADLINE_EXCEEDED` / `DEEPSEEK_SUBMISSION_UNKNOWN`
通过现有 Java 失败事件上报 UNKNOWN 和显式空回执；Java 无可查询回执时终止并释放冻结积分，
不能再自动发送新的生成请求。失败 span 的调用数和已返回 Token 仅是观测下界，未知用量/金额
不等于零；当前失败事件契约不持久化完整费用统计。

## WORKFLOW Worker

WORKFLOW Worker 被动消费 Java 声明的 `zhixiaohe.agent.workflow`（routing key
`agent.workflow`），复用同一 Rabbit 手工 ACK、JavaInternalClient HMAC/Trace 连接池和
Tool Registry/Policy/Executor。它严格读取 Java 计划级不可变 Authorized Context，在本次执行
内构造只读 Catalog、Memory、Task Workspace 和 Competitor 工具绑定；不回查 Catalog，也不跨
计划缓存业务状态。QUERY、ANALYZE、SYNTHESIZE 仍只执行已注册工具，COMMIT 和媒体 Provider
永不进入该运行时。

PREPARE 只接受 Java 已持久化且授权的 proposal-only `taskDraftId`，随后调用 Java Workflow
Task activation。Java 原子划拨计划额度、创建既有 IMAGE/VIDEO/GEO/ARTICLE Task 并将 Execution
fencing 为 `WAITING_TASK` 后，Workflow Worker 立即停止 heartbeat 和后续写入；不会自行调用媒体
Provider、创建 Task、扣积分或把等待任务误报成 `WAITING_INPUT`。Task 终态由 Java 发布
`resumeOnly=true` 的下一代消息，Worker 从新 Context 跳过已完成步骤并使用 `taskResultRef` 继续
DAG。activation、Completion 和 Observation 均使用稳定幂等 UUID；旧 execution token 被拒绝后
不会继续写入。独立进程仅监听回环地址 `127.0.0.1:8014`；Java compatible-worker 开关只有在
版本、队列和联合 Gate 均确认后才能开启。

同一 OpenTelemetry 管线将一次 WORKFLOW 消费串成 Rabbit、claim、Context、execution、step/tool、
子任务 activation、durable wait、resume、observation 和 complete 的父子 Span；重投递仍使用
Trace Link。工作流指标只使用阶段、结果、任务类型、稳定错误类别和重试性等低基数标签，不把
计划/步骤/执行/Trace ID、业务正文或 Provider 标识放入标签。对应故障注入矩阵只验证现有
ACK/NACK、fencing、等待恢复与 UNKNOWN recovery 边界，不会生成付费 Provider 请求。

真实 ARTICLE 质量 Gate 固定使用 20 条无隐私合成用例，覆盖标题、提纲、正文、局部改写、
授权素材/GEO 摘要和事实不足场景。命令默认拒绝执行；只有测试服务器受控环境明确增加
`--execute` 才会调用 DeepSeek。输出只含通过率、稳定错误码、Token 和延迟聚合，不含文章
正文、经营摘要或凭据。Gate 对阶段契约、事实边界、越权业务断言、标题重复与空泛内容、局部
改写范围及中文可读性分别给出确定性指标；这些检查不依赖模型裁判：

```powershell
uv run python -m app.article.gate
uv run python -m app.article.gate --execute
```
