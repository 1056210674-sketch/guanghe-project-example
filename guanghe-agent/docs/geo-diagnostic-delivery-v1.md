# GEO 诊断执行与只读解读 V1

Python 只执行冻结采样矩阵与只读解读，不连接业务 PostgreSQL，不建任务、扣费、生成 PDF 或上传对象存储。
Java 拥有权限、定价、采样持久化、分类指标、交付租约、完整回答附录、PDF 与私有 OSS。

## 2026-10-23 咨询式报告解读 V11

当前解读 Bundle 为 `GEO_DIAGNOSTIC_ANALYSIS_V11`。沿用旧报告字段与统计分工，新增可选
`strategy.version=consulting-v1`，包括核心判断、认知观察、实际问题的意图分层和建议路线图。
旧报告可以缺失 strategy 及建议增强字段；新 V11 生成必须填写 strategy，且建议必须包含
currentState / businessImpact / deliverable / acceptance / ownerRole / timeframe。
后两项表达建议角色和建议阶段，验收是可核对的交付内容或同口径复测，不是效果承诺。

所有新增实质主张复用逐字引文校验。关键词层只允许冻结问题 ID，BRAND 对应口碑组，其余层
对应自然发现组，所引成功回答必须属于该层问题；层与问题 ID 不重复。路线图使用本报告
recommendations 零基索引，阶段内不重复且不越界。历史数据不补造新字段。

输入 answerExcerpt 上限为 1600 字，来源摘要仍 500 字，整体 wire 仍限 256 KiB。
Java 提供连续原文窗口，Python 不拼造片段。模型视图保留实际来源 title/url，优先为每个分类/平台
保留一个真实来源，再逐回答逐条轮询，不让首回答独占来源预算；有来源时至少保留一条。
先减少来源数量和摘要，再以50字阶梯裁剪回答，不使用1600→800→400的粗粒度减半。
分类指标、全部成功/失败/缺失身份与全文品牌命中矩阵始终保留。极端预算时省略重复的
逐平台统计文字、减少来源/回答摘录；最大70单元且长ID耗尽预算时才允许缩至100字，仍保留分类
代表回答及完整矩阵，不删改 Java 原证据与 inputHash；未展示片段不可作引文。

仅本解读阶段预算为18K初始输入、24K含完整Schema/最坏修复、16384输出Token上限。
仍最多两次模型HTTP（一次生成、至多一次既有格式修复），总deadline取调用方与接收后120秒中较早者。
仅DeepSeek诊断operation的HTTP读取覆盖为120秒，连接/池最多10秒、写入最多30秒；
共享客户端默认不变，外层asyncio.timeout继续保证累计耗时不越过剩余deadline。不增加采样、搜索或业务
重试，不改变积分结算；真实模型Token仍按实际用量记录。仅使用现有配置的DeepSeek Gateway，
不切模型/Provider。下文V1–V10说明保留
用于历史行为溯源，V11 上述增量优先。

Prompt支持咨询版章节深度，但不新增Schema：核心判断250字内，发现解释真实现状/回答/业务意义，
认知项按证据通常约5条、可少；行动通常5–8项，分别填写现状、影响、具体改法、交付、验收、
建议角色与阶段；内容机会通常3–6题说明为什么、写什么、怎么验证；路线图通常3–4个依赖阶段。
条数与篇幅是编辑指引不是硬门槛，缺证据不凑页，不添加量化效果承诺或已确认分工。

## 采样交接

复用既有 GEO Rabbit、Claim-before-ACK、执行租约、Provider Registry/Executor、JavaInternalClient。
六字段仍为 `mode/topicId/questionIds/platformCodes/sampleMode/region`；诊断专用 `DIAGNOSTIC_RUN`
只允许 STANDARD、每组合一次，3..10 题、1..7 平台、最多 70 单元；Provider HTTP 预算只服从
Java 的实际签名冻结值，配置范围 1..1000、默认 200，不因矩阵大小或 retry 获得额外调用授权。
普通 MONITOR_RUN、DEEP 与聊天 MonitorActionContext 保持原语义，不接受诊断模式。
准备候选现使用只读 GEO_DIAGNOSTIC_PREPARATION_V5：阶段专用工具 Schema 固定 null/空数组与条数，
系统只含平铺阶段规则，不放嵌套占位模板。COMPETITORS 的外部搜索只使用公开品牌与目标地域；
自由介绍、人工理由、无公开来源支持的手工名字留在模型理解与完整输入校验中，不作为搜索词。

诊断授权快照单独接受 BRAND_DISCOVERY/BRAND_REPUTATION，200 字品牌、最多五个竞品与稳定 M1 字符串 ID；
Java materialize 的 topic/question 仍是真实 Snowflake ID。Provider 只收到该问题和采样/Trace 标识，
不注入监测目标、品牌档案、知识库或以前聊天。自然问题中不点品牌，口碑问题显式点品牌。

保留现有第一轮结束后的有限临时失败补采；同一预算/截止时间内最多一轮，不补采永久错误。
恢复时已经持久化的成功和失败单元都跳过，不覆盖保存证据、不重复收费。
全部失败也执行采样交接，不改用通用 reportFailure；Java run=FAILED/settled=0，不虚称成功监测。

完成使用 `geo-diagnostic-sampling-v1`、SYSTEM / `deterministic-geo-diagnostic-sampling-v1`、零模型 Usage。
只有采样交接说明，结构化解读数组为空。六字段回执保持不变，但诊断专用 parser 接受
taskStatus=RUNNING，或交接重放时 Java 真实 SUCCEEDED/PARTIAL/FAILED/SYSTEM_TERMINATED；
runStatus=SUCCEEDED/PARTIAL/FAILED/SYSTEM_TERMINATED、reportId=null、settled/released>=0。
Python 本地诊断 snapshot 为 HANDOFF_ACCEPTED；采样执行终态不是业务任务 READY/PDF-ready。

## 解读接口

HMAC `POST /internal/v1/geo/diagnostic-analyses` 只接收 Java 冻结的 jobId、inputHash、UTC deadlineAt、
title、evidence。请求最多 262144 UTF-8 字节：完整矩阵 70 个 SUCCEEDED/FAILED/MISSING 状态，
成功摘录最多 700 字、来源最多 100 个/片段 500 字、每样本 sourceIds 最多 50。
analysis-only samples 不重复 questionText；完整问题文本只放 questions 字典，按 questionId/category
绑定。Browser 与 PDF 从 Java 冻结问题恢复展示全文，不以模型摘录替换。
单独列出自然发现与口碑分类指标；数字权威是 Java 从原始完整回答计算的结果，不从模型摘录重算。
有效回答未提及是 0，失败/缺失不是 0；自然可见度分母只含发现问题有效回答，分母为空时 rate=null。

复用原 API DeepSeek Gateway、分析 Semaphore、Harness、Trace；仅解读 operation 使用既有 JSON_OBJECT。
只返回严格 report 的六个字段；新回执 Prompt 为 GEO_DIAGNOSTIC_ANALYSIS_V10。
原 V1/V2/V3/V4/V5/V6/V7/V8/V9 Bundle 和已经 READY 的历史报告原样保留，不更新旧 evidence/inputHash 或覆盖报告。
单次请求期限最多 60 秒，模型最多两次 HTTP（一次严格修复），不扩大全局预算。
仅该解读 operation 的输出 max_tokens=6144，输入 16K 不变。引导摘要约 200 字、重点通常 3..5 项，
建议/选题各 3..5 项但不强凑；每项通常一条 20..60 字精确引证，正文约 1800..2400 字。
这些是编辑篇幅指引，不缩 Schema、过滤输出或新增关键词 validator；finish_reason=length 仍严格失败/最多一次修复。
原始指令/证据投影最多 11K 估算 Token；完整 code-owned Schema 注入系统消息，实际 JSON 请求与
最坏八条/六段安全修复合计最多 16K。projection、Gateway 和 QA helper 复用相同消息/预算渲染器，
包含 Schema、消息序列化转义和 response_format，不能只计原始证据消息。
未知字段、外壳、无效 JSON、来源/分类/连续原文不符仍拒绝；不删除错误字段、不 unwrap 或放宽引用。

新快照每个 sample.matchedBrandIds 由 Java 完整回答匹配 OWN 与选定竞品稳定 ID，最大六项，
失败/缺失为 []。旧 V1 冻结证据可缺失或 null，仅表示未知，不能改旧 hash、填 [] 或从摘录猜全文。
完整已知组须与平台提及/口碑 recognized 指标精确相符；混合未知仅检查已知下界与可能上界。
模型视图 brandIdOrder 只发送一次稳定品牌 ID；matrixByStatus[status][qIndex:pIndex] 为该顺序的匹配
索引数组或未知 null，保留所有成功、失败、缺失单元；competitors.brandIndex 使用同一顺序。
qIndex/pIndex 是完整 questions/platformCodes 字典的零基索引；真实 unitKey 为
questions[qIndex].id+":"+platformCodes[pIndex]+":1"。展示 samples.unitKey 和输出引用仍是真实完整 ID。
模型 metrics.counts、discovery[platform].counts、reputation[platform].counts 的字段顺序一次定义为
[expectedSamples,validSamples,failedSamples,missingSamples]；discovery[platform].mentions 按
brandIdOrder 同序，为 [mentionedSamples,mentionRate]，reputation[platform].recognizedSamples 保持原值。
两组分类独立，所有计数/比例可逐值逆向恢复。这是模型-only编码，不修改 wire 或持久化证据。
模型-only measuredFacts 由原始 Java metrics 直接格式化每平台自有/竞品的被提及回答份数；
合计使用平台 numerator/valid denominator 分别加总，不平均比例、重算片段或计词频。
来源/有引用回答计数只代表 Java 冻结分析视图保留项，完整原始引用数量以最终附录为准，
不将 Java 预过滤或 Python 模型 sourceIds 裁剪当最终无引用。
正文侧重证据定性，不重复可见度数字或无据的全覆盖断言；没有词法关键词拦截器。
V5 区分名字提及、明确推荐、本地实证；展示过明确自有推荐时不能说仅字面提及或未独立推荐。
对照只限同一回答且正反引文支持，不泛化平台偏好、排序/效果；诊断允许确认优势，不预设缺口。
行动引文须直接支持实质主张，未确认官网资料不以无关业务引文包装成入口缺失；证据不足省略。
时间/口径差异不自动当冲突；客户正文使用友好用语，不复制实现字段名。以上仅 Prompt 指引，不加词法 validator。
V6 仅补工具参数的标准 JSON 序列化指引：ASCII 双引号、反斜杠、换行须转义，禁止代码围栏、
JavaScript 对象、注释、NaN/Infinity、尾逗号与尾随字符；精确引文只在 JSON 编码层转义，不删改原文。
诊断修复增加同一序列化指引，并沿用允许的阶段/脱敏字段反馈与同源最坏预算；解析器、Schema、
引文校验和普通 Gateway 不变。不以 invalid_json 阶段推断具体坏字符，也不作宽松解析或自动补 JSON。
V7 仅将 readonly_geo_diagnostic_analysis 切到 response_format=json_object，并附完整严格输出 Schema。
JSON mode 只保证语法，不取代 Schema/来源/分类/精确引文校验；空/空白、length、无效 JSON 和错误
Schema 都只能一次既有修复，仍错误即失败，不 unwrap、不切 Provider 或增加第三请求。
官方说明 JSON mode 可能返回空内容，因此 empty_content 纳入诊断安全反馈与同源 reserve；
其他 readonly、准备、普通 GEO、article 与原生采样模式不变。
见 [DeepSeek JSON Output](https://api-docs.deepseek.com/guides/json_mode/)。
V8 仅在模型 facts.limitations 排除精确前缀 `准备阶段提示（不代表最终诊断报告的全部证据）：`
标记的准备 warnings：它们是上阶段的查询/卡片限制，不是当前采样的观察或最终报告证据。
不按“准备/官网/来源”等关键词过滤；无此前缀的当前采样限制及 legacy 文本仍保留。projection.clipped
包含阶段 notes 的省略，但原始 evidence/limitations/inputHash、历史报告、指标与全文标记完全不改。
JSON mode/Gateway/Schema、采样答案/来源裁剪规则、11K/16K/2次/60秒/6144预算不变。
V9 的通用编辑指引逐项核对并列主张对应的回答与量词，不把某份回答的细节扩大到同平台全部回答；
全国/全球规模与目标城市的本地线索分别表述。仅模型输入用 `excerptClipped` 表示解读摘录裁剪，
不是原始回答采集被截断；输入 wire 继续保留 `answerTruncated`，原始证据和 Schema 不改。
固定客户说明及模型计数提示去除 Java/Python/业务控制面等实现术语，仍明确完整答案与来源见附录。
不新增品牌/平台特例、输出关键词拦截或质量评分调用；新增离线检查验证旧 Bundle 字节、
逐回答不同事实和裁剪标记不串用、原 wire 不变及实际 JSON/最坏修复预算。文案仍需真实证据复核。
V10 仅收紧归属编辑指引：按 unitKey 分述细节，不能把同平台另一回答的详情合并给当前回答；
建议优先描述动作，避免复述跨组年份/数量，确需复述须逐条引用同组实际证据。
未新增词法拦截、品牌/年份特例、模型评分或预算；离线回归同时保留同平台不同问题及两组证据的边界。
投影先缩来源 snippet，再减少来源条数（必要时至零），最后才缩采样回答，仍使用同源 Schema/repair 预算。
模型不得将摘录没品牌说成全文没品牌，不据不明场景关联推信息缺口或 P0；匹配名称也不等于背书或因果。
准备阶段警告仅适用于准备时点，不泛化为最终来源缺失；旧年份/矛盾原回答不当作当前经营事实。
模型视图单独裁剪介绍、片段与重复字段；
samples 必要时按分类/平台轮转选择有效摘录，不按品牌是否被提及筛选。省略摘录的成功 ID 仍在
SUCCEEDED 分组，不是失败或零，也不能支持模型引文。
Java 完整请求、指标、用户手工资料、全部成功/失败记录和稳定来源 ID 不修改。
只有实际展示的 answerExcerpt/source.snippet 可以支持模型 quote，Java 再按原摘录核对。
解读投影不是交付全文，完整回答附录必须来自 Java 保存的原始采样记录。

findings 为 DISCOVERY/REPUTATION/COMPETITION/PUBLIC_RESEARCH；前三者需有效对应分类 unitKey，
公开研究只引真实 sourceId。证据双 ID 时须来源属于该样本且 quote 同时是两段原文的精确 substring。
`contentTopics` 为 1..10 个内容选题蓝图，逐项包含顾客问题、角度、仅自有品牌词与 1..5 条真实依据，
不是写稿或投稿任务；未确认品牌词、竞品词和无依据引用均拒绝。
有实际有效样本的发现/口碑章节必须独立呈现；有选定竞品与发现有效样本时必须有同批竞品对照。
缺有效某组时明确限制，不能用单条公开研究替代完整诊断。
正文不编造 URL；引用原文可含原 URL，链接由 Java 从真实来源渲染。
口碑仅解释 AI 本次怎样介绍和评价，不代表消费者真实评价、市场份额或数值口碑评分。
单次截面不证明优化提升或因果；程序追加这些限制并去重限于 20 条，不伪造模型内容或 PDF 状态。

所有单元 FAILED/MISSING 时不调用 Python 模型：Java 生成明确无数据报告及 PDF，业务 PARTIAL 交付。
解读/PDF retry 仅 Java delivery，不能再次进入采样/outbox/wallet；模型 Usage 与采样成本分别记录。
本仓离线检查使用 HTTP/模型替身，不代表真实 Provider 文案质量或 PDF 已验收。

## 私有 delivery-only QA

`scripts/diagnostic_analysis_qa.py` 仅供 root 对既有冻结六样本作解读质量复核，默认仅离线检查。
运行目录固定为测试服务器的 `/opt/guanghe-example/private/diagnostic-qa`，必须 root-owned
0700/无 symlink；`quality-analysis-request.json` 必须 root-owned 0600。输入由 Java 完整已保存回答
派生全文匹配标记，不由 Python 摘录推断；不更新旧 evidence/inputHash 或正式 READY 报告。
显式 `--execute` 仅允许固定测试 hostname，用已有 Internal HMAC signer 向本机分析 API 发一次 POST，
只刷新 60 秒 deadline，原 Gateway 最多两次模型 HTTP 不变；没有采样、结算、DB 或交付 mutation。
发送前 CREATE_NEW/fsync 持久化 one-call guard，任何超时、错误或未知结果都不删除、不重放；旧目录/guard 不改。
禁止 HTTP retry/redirect。成功响应先严格验证身份、V10、usage 与展示证据，CREATE_NEW 0600 写
`quality-analysis-response.json`，不覆盖任何旧报告。stdout 只含 QA_ONLY_NOT_PUBLISHED、版本、
用量与裁剪上限，不输出回答、URL、报告正文、凭据或上游错误。真实执行需 root 单独授权，离线测试不授权调用。

## 后续只读优化讨论

既有 `resources_read` 的 GEO_DIAGNOSTIC_REPORT purpose 只绑定当前授权 SPACE 项目与服务器引用。
诊断 ID/version 在冻结 projection 中，普通 reportId/reportAttempt/productId 为空，不混入
LATEST_GEO_REPORT、geoInsightContext 统计或文章 geoReportId。模型不能指定自由诊断 ID、
sampleRefs 或 reportPolicy，不创建任务或费用；Java 再验 owner、项目和已交付状态。
