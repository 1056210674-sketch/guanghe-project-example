"""Prompt Bundle validation tests."""

from pathlib import Path

import pytest

from app.agents.catalog import load_catalog
from app.common.enums import TaskType
from app.common.settings import default_bundle_path
from app.model_gateway.gateway import GENERAL_RESPONSE_INSTRUCTION
from app.prompts.assembly import CommercePromptSet
from app.prompts.loader import BundleLoadError, load_bundle


def test_default_bundle_loads_with_stable_hashes() -> None:
    loaded = load_bundle(default_bundle_path())
    assert loaded.bundle.bundle_version == "agent-bundle-v1.62.1"
    catalog = load_catalog()
    catalog.validate_bundle(loaded)
    assert {item.execution_release for item in catalog.agents.values()} == {"native-20261013-v1"}
    assert loaded.bundle.profiles[0].version == "42"
    assert loaded.bundle.profiles[0].graph_version == "34"
    assert len(loaded.bundle_hash) == 64
    assert len(loaded.prompt_hash) == 64
    prompt_versions = {prompt.code: prompt.version for prompt in loaded.bundle.prompts}
    assert prompt_versions["CREATIVE_NATIVE_LOOP_V1"] == "8"
    assert prompt_versions["CREATIVE_ACTION_REVIEW_V1"] == "3"
    assert prompt_versions["GEO_NATIVE_LOOP_V1"] == "24"
    assert prompt_versions["ARTICLE_ACTION_REVIEW_V1"] == "4"
    assert prompt_versions["COMMERCE_ARTICLE_AUTHORITY"] == "4"
    assert prompt_versions["COMMERCE_ARTICLE_PLAYBOOK"] == "22"
    assert prompt_versions["COMMERCE_ARTICLE_TITLE"] == "14"
    assert prompt_versions["COMMERCE_ARTICLE_OUTLINE"] == "17"
    assert prompt_versions["COMMERCE_ARTICLE_BODY"] == "19"
    assert prompt_versions["COMMERCE_ARTICLE_REWRITE"] == "4"
    assert loaded.bundle.profiles[0].task_types == (
        TaskType.GEO,
        TaskType.IMAGE,
        TaskType.VIDEO,
        TaskType.ARTICLE,
    )
    assert loaded.bundle.profiles[0].model_policy_codes == ("DEEPSEEK_PRIMARY",)
    assert tuple((policy.code, policy.provider) for policy in loaded.bundle.model_policies) == (
        ("DEEPSEEK_PRIMARY", "DEEPSEEK"),
    )
    intent_prompt = next(
        prompt.system
        for prompt in loaded.bundle.prompts
        if prompt.code == "COMMERCE_INTENT_CLASSIFIER"
    )
    assert "schemaVersion" in intent_prompt
    assert "intent-router-v2.2" in intent_prompt
    assert "即使同时需要经营空间与该空间中的商品上下文，仍然是 STANDARD" in intent_prompt
    assert "taskWorkspace.pendingInteraction 是唯一允许" in intent_prompt
    assert "recentHistory 里的普通编号列表不是工作流状态" in intent_prompt
    assert "GENERAL+ANSWER，不是付费文章流程" in intent_prompt
    assert "图先别做，帮我写两句咖啡机宣传文案，别吹太过" in intent_prompt
    assert "hasActiveDraft=false、hasActiveTask=false、runningTasks=[]" in intent_prompt
    for intent in ("GENERAL", "GEO", "IMAGE", "VIDEO", "ARTICLE", "AMBIGUOUS_CONFIRMATION"):
        assert intent in intent_prompt
    assert loaded.bundle.profiles[0].tool_names == (
        "task_draft.propose",
        "context.catalog.inspect",
        "context.resources.search",
        "context.resources.read",
        "context.memory.search",
        "context.task_workspace.inspect",
        "analysis.competitor.compare",
        "analysis.evidence.aggregate",
        "workflow_plan.propose",
    )
    assert loaded.bundle.profiles[0].readonly_planner_prompt_code == ("COMMERCE_READONLY_PLANNER")
    assert loaded.bundle.profiles[0].general_response_prompt_code == "GEO_GENERAL_RESPONSE"
    assert (
        loaded.bundle.profiles[0].general_response_review_prompt_code == "GENERAL_RESPONSE_REVIEW"
    )
    assert loaded.bundle.profiles[0].readonly_synthesis_prompt_code == (
        "COMMERCE_READONLY_SYNTHESIS"
    )
    assert loaded.bundle.profiles[0].prompt_layer_codes == (
        "COMMERCE_TRUTH_AND_AUTHORITY",
        "COMMERCE_DECISION_PROTOCOL",
        "COMMERCE_ADVISOR_PLAYBOOK",
        "COMMERCE_TOOL_DECISION",
        "COMMERCE_RESPONSE_STYLE",
    )
    assert loaded.bundle.profiles[0].domain_playbook_prompt_codes == {
        TaskType.GEO: "COMMERCE_GEO_PLAYBOOK",
        TaskType.IMAGE: "COMMERCE_IMAGE_PLAYBOOK",
        TaskType.VIDEO: "COMMERCE_VIDEO_PLAYBOOK",
        TaskType.ARTICLE: "COMMERCE_ARTICLE_PLAYBOOK",
    }
    assert loaded.bundle.profiles[0].article_stage_prompt_codes == {
        "TITLE": "COMMERCE_ARTICLE_TITLE",
        "OUTLINE": "COMMERCE_ARTICLE_OUTLINE",
        "BODY": "COMMERCE_ARTICLE_BODY",
        "REWRITE": "COMMERCE_ARTICLE_REWRITE",
    }
    assert loaded.bundle.profiles[0].execution_prompt_codes == {
        TaskType.GEO: "COMMERCE_GEO_SYNTHESIS",
        TaskType.IMAGE: "COMMERCE_IMAGE_PROVIDER",
        TaskType.VIDEO: "COMMERCE_VIDEO_PROVIDER",
    }
    prompts = {prompt.code: prompt.system for prompt in loaded.bundle.prompts}
    truth_prompt = prompts["COMMERCE_TRUTH_AND_AUTHORITY"]
    assert "不是整个 Context 都可作为商品事实" in truth_prompt
    assert "品类常识" in truth_prompt
    assert "用户提出的主题、口号或创意方向" in truth_prompt
    assert "当前对话尚未接入图片像素分析" in truth_prompt
    assert "不得编造光禾AI智能体框架的页面、菜单或可用功能" in truth_prompt
    assert "宣传文案中的功能和体验承诺同样是商品事实" in truth_prompt
    article_title_prompt = prompts["COMMERCE_ARTICLE_TITLE"]
    assert "恰好 5 个" in article_title_prompt
    assert "价格、材质、产地、工艺、规格" in article_title_prompt
    assert "有已知业务关联、可以用稳定知识展开的具体议题" in article_title_prompt
    assert "编辑推荐的最优题目" in article_title_prompt
    article_plan_prompt = prompts["COMMERCE_ARTICLE_OUTLINE"]
    assert "文章创作方案" in article_plan_prompt
    assert "summary" in article_plan_prompt
    assert "coreMessage" in article_plan_prompt
    assert "targetAudience" in article_plan_prompt
    assert "targetPlatform" in article_plan_prompt
    assert "riskNotes" in article_plan_prompt
    tool = next(tool for tool in loaded.bundle.tools if tool.name == "task_draft.propose")
    assert tool.input_model == "TaskDraftPrepareInput"
    assert tool.output_model == "TaskDraftProposal"
    assert tool.tool_group == "TASK_PREPARATION"
    assert tool.risk_level == "PREPARE"
    assert tool.execution_mode == "INLINE"
    assert tool.retry.max_attempts == 1
    assert tool.cost.units == 0
    assert "不得出现 Java、Python" in prompts["COMMERCE_RESPONSE_STYLE"]
    assert "不在问题中点名自家商品" in prompts["COMMERCE_GEO_PLAYBOOK"]
    assert "不得因为侧栏同时选中了商品" in prompts["COMMERCE_GEO_PLAYBOOK"]
    assert "用户转而咨询、闲聊" in prompts["COMMERCE_DECISION_PROTOCOL"]
    assert "文章任务、媒体推荐或投稿已经自动创建" in prompts["COMMERCE_GEO_SYNTHESIS"]
    assert "这类低风险核查动作只放在 summary 结尾" in prompts["COMMERCE_GEO_SYNTHESIS"]
    assert "不能把“本次零提及”等同于全网无知名度" in prompts["COMMERCE_GEO_SYNTHESIS"]
    assert "自家目标零提及不影响保留有原文依据的竞品候选" in prompts["COMMERCE_GEO_SYNTHESIS"]
    assert "禁止仅凭品牌名脑补优势" in prompts["COMMERCE_GEO_SYNTHESIS"]
    assert "八个分析字段" in prompts["COMMERCE_GEO_SYNTHESIS"]
    assert "使用 GEO+ANSWER 或 GEO+ANALYZE" in intent_prompt
    assert "只有明确要求新建、再跑、重新监测才 GEO+PREPARE" in intent_prompt
    assert "候选和正式竞品不同" in truth_prompt
    assert "百分点变化不是增长百分比" in truth_prompt
    assert "不代表确认任何业务操作" in intent_prompt
    assert "两期不可比不等于只有一次监测" in truth_prompt
    assert "不自动加入当前专题、监测问题、自动计划或持续跟踪" in truth_prompt
    assert "不可比不等于没有历史" in prompts["COMMERCE_READONLY_SYNTHESIS"]
    assert "引文不支持正向或负向因果归因" in prompts["COMMERCE_READONLY_SYNTHESIS"]
    assert "竞品目录已确认不等于当前专题已跟踪" in prompts["COMMERCE_READONLY_SYNTHESIS"]
    assert "历史助手回复中的技术标识和未经证实的归因" in prompts["COMMERCE_RESPONSE_STYLE"]
    assert "只选择本轮商家问题需要查看的已有证据引用" in prompts["COMMERCE_GEO_READONLY_SELECTOR"]


def test_article_prompts_deliver_complete_topic_articles_not_short_brand_cards() -> None:
    loaded = load_bundle(default_bundle_path())
    prompts = {prompt.code: prompt.system for prompt in loaded.bundle.prompts}
    playbook = prompts["COMMERCE_ARTICLE_PLAYBOOK"]
    outline = prompts["COMMERCE_ARTICLE_OUTLINE"]
    body = prompts["COMMERCE_ARTICLE_BODY"]
    # Prompt contract checks are not a substitute for reading actual generated articles.
    assert "专业中文媒体品牌文章编辑" in playbook
    assert "不是给委托方的运营方案或聊天咨询" in playbook
    assert "中立、匿名、短介绍、报告解读及局部改写均服从用户范围" in playbook
    assert "不把完整稿降成几句公司简介" in playbook
    assert "不将完整专题缩成简介" in outline
    assert "让读者获得新的认识" in body
    assert "editorialReview只供内部审稿" in playbook
    assert "仅在更清楚时采用，不强制齐全" in playbook


def test_article_prompts_keep_user_requirements_without_freezing_assistant_sections() -> None:
    loaded = load_bundle(default_bundle_path())
    prompts = {prompt.code: prompt.system for prompt in loaded.bundle.prompts}
    playbook = prompts["COMMERCE_ARTICLE_PLAYBOOK"]
    outline = prompts["COMMERCE_ARTICLE_OUTLINE"]
    body = prompts["COMMERCE_ARTICLE_BODY"]
    for length in ("400\uff5e800", "1000\uff5e1500", "1800\uff5e2500"):
        assert length in playbook
    assert "150\uff5e300" not in playbook + body
    assert "用户明确篇幅优先" in playbook
    assert "服从明确篇幅与扩充要求" in body
    assert "writingReference仅帮助解析指代和所选主题" in playbook
    assert "本身不授权新动作或新增品牌事实" in playbook
    assert "用户亲自列出或明确指定保留的章节职责才应保留" in playbook
    assert "用户仅要求“四部分”只固定数量" in playbook
    assert "不固定助手的具体标题措辞、章节论述或因果判断" in playbook
    assert "writingReference里的助手章节可重新编排" in outline
    assert "原USER指定的文体、章节职责、篇幅和节数" in outline
    assert "承接相邻讨论中的标题、结构和改法" not in playbook
    assert "用户已经给出四块结构或明确“按刚才扩充”时" not in outline
    assert "不要求用户再确认" in outline
    assert "保持key/heading/顺序/数量" in body
    assert "沿用原USER文体与方案主线" in body


def test_article_prompts_distinguish_general_knowledge_and_confirmed_brand_facts() -> None:
    loaded = load_bundle(default_bundle_path())
    prompts = {prompt.code: prompt.system for prompt in loaded.bundle.prompts}
    authority = prompts["COMMERCE_ARTICLE_AUTHORITY"]
    playbook = prompts["COMMERCE_ARTICLE_PLAYBOOK"]
    outline = prompts["COMMERCE_ARTICLE_OUTLINE"]
    body = prompts["COMMERCE_ARTICLE_BODY"]
    assert "具体材料、可解释的稳定知识与有条件的分析" in playbook
    assert "一般知识、假设例子与企业实绩分开" in playbook
    assert "一般知识与品牌事实清楚区分" in outline
    assert "一般知识和条件性建议不是企业能力或已取得效果" in body
    assert "它表示用户确认，不表示独立核验或第三方背书" in authority
    assert "当前项目纠正、删除和当前USER限制优先" in authority
    assert "简短认可承接写作意图，不固定助手的具体标题措辞、章节论述或因果判断" in playbook
    assert "[虚构编辑示例，仅示范材料到公众段落，不是本次事实]" in outline
    assert "不替品牌声称具体交付承诺" in outline
    for code in ("COMMERCE_ARTICLE_TITLE", "COMMERCE_ARTICLE_PLAYBOOK"):
        assert "澄页语言" not in prompts[code]
    assert "不是本次事实，不得移用" in playbook


def test_article_editorial_scope_and_fact_trust_survive_plan_to_body() -> None:
    prompts = {item.code: item.system for item in load_bundle(default_bundle_path()).bundle.prompts}
    native = prompts["GEO_NATIVE_LOOP_V1"]
    playbook = prompts["COMMERCE_ARTICLE_PLAYBOOK"]
    title = prompts["COMMERCE_ARTICLE_TITLE"]
    outline = prompts["COMMERCE_ARTICLE_OUTLINE"]
    body = prompts["COMMERCE_ARTICLE_BODY"]
    # Editorial requirements, not a claim that a deterministic fixture proves prose quality.
    assert "面向公众的媒体品牌文章编辑身份" in native
    assert "可用材料、正文将解释或呈现什么" in native
    assert "用户确认资料不等于独立核验" in native
    assert "每个阶段都从brief原USER要求、有效项目偏好和材料核对文体、受众、主题与篇幅" in playbook
    assert "正文将兑现的内容承诺和已有材料如何支撑" in title
    assert "不依赖rationale传递新的要求或事实" in title
    assert "不依赖标题阶段的rationale" in outline
    assert "summary写明读者、文体和文章主线" in outline
    assert "科普、产品稿、决策稿不采用同一种开头和结构" in body
    assert "不把所有稿件都写成采购指南" in playbook
    assert "故事、案例分享须有相应真实材料" in playbook
    assert "用户确认不等于独立核验" in playbook
    assert "结论范围与依据相称" in playbook
    assert "首节前两句直接回答标题" not in body
    assert "一个具体读者问题和一句直接回答" not in native


def test_article_plan_corrects_assistant_premises_without_promoting_brand_facts_to_proof() -> None:
    loaded = load_bundle(default_bundle_path())
    prompts = {item.code: item.system for item in loaded.bundle.prompts}
    prompt_set = CommercePromptSet.from_bundle(loaded.bundle, loaded.bundle.profiles[0])
    # Assembly checks prove the boundaries reach all stages, not generated prose quality.
    for stage in prompt_set.article_stage_prompts:
        system = prompt_set.for_article_stage(stage)
        assert "助手提议是可纠错草案，不是事实或判断前提" in system
        assert "客户同时提供A和B，不表示做A必须购买B或同一家交付" in system
        assert "不证明专业水平或效果" in system
        assert "用户明确指定的标题、文体、篇幅、章节职责、节数和修改重点优先" in system
        assert "本身不授权新动作或新增品牌事实" in system
        assert "相关品牌事实可集中一段或就近介绍" in system
        assert "不贬低或影射同行" in system
    native = prompts["GEO_NATIVE_LOOP_V1"]
    assert "从读者需求和可用材料形成观点" in native
    assert "不使提议中的事实或因果判断成立" in native
    outline = prompts["COMMERCE_ARTICLE_OUTLINE"]
    assert "这是正文结构固定前的编辑工作" in outline
    assert "section.brief提供正文材料" in outline
    assert "不是审稿指令" in outline
    assert "限制和证据缺口放riskNotes" in outline
    assert "对应公众段落示例" in outline
    assert "先把同一版手册、目标语言和交付格式交给服务方" in outline
    assert "澄页语言提供产品手册翻译，咨询这项业务时" in outline
    assert "术语表记录名称与含义的对应关系" in outline
    assert "本例解释原理，不套采购比较结构" in outline
    assert "错误草案" not in outline
    body = prompts["COMMERCE_ARTICLE_BODY"]
    assert "**先确定使用对象**。再约定验收范围。" in body
    assert "在固定结构内改为有依据的解释" in body


def test_article_length_budgets_use_existing_briefs_without_extra_repair_or_cutoff() -> None:
    prompts = {item.code: item.system for item in load_bundle(default_bundle_path()).bundle.prompts}
    playbook = prompts["COMMERCE_ARTICLE_PLAYBOOK"]
    outline = prompts["COMMERCE_ARTICLE_OUTLINE"]
    body = prompts["COMMERCE_ARTICLE_BODY"]
    assert "writingConstraints是运行时从权威brief识别的格式要求" in playbook
    assert "优先于较粗的targetLength及助手参考" in playbook
    assert "sections必须恰好该数量" in outline
    assert "在每节brief标注约多少字" in outline
    assert "用户给定的节数优先，不强套四节" in outline
    assert "heading表达公众可读的具体内容，不写预算或内部字段" in outline
    assert "表格/FAQ计入所属小节" in outline
    assert "不强制齐全" in playbook
    assert "在本次生成内估算总长度" in body
    assert "不按字符截断，不要求用户重试，不新增付费修复" in body


def test_article_continuation_prompts_preserve_server_authority_and_single_draft_scope() -> None:
    loaded = load_bundle(default_bundle_path())
    prompts = {prompt.code: prompt.system for prompt in loaded.bundle.prompts}
    native = prompts["GEO_NATIVE_LOOP_V1"]
    review = prompts["ARTICLE_ACTION_REVIEW_V1"]
    assert "不要求出现“文章”两个字或任何固定词" in native
    assert "简短接受写作提议先核对articleContinuation" in native
    assert "不要求用户再确认标题" in native
    assert "EDIT_EXISTING只表示修改旧稿，不是重新生成整篇的授权" in native
    assert "当前“好/认可/继续做”等紧接唯一明确的完整新稿提议" in review
    assert "简单肯定或继续不能靠历史承诺自授确认" in review
    assert "不是执行授权" in review
    assert "一整版新稿" in native and "一整版新稿" in review
    assert "articleContinuation.authorized=true" not in native
    assert "历史只用来解决“上面/这个方向”的指代" in review
    assert "当前USER的否定、限范围、暂缓要求优先" in review


@pytest.mark.parametrize(
    "boundary",
    (
        "不同平台及同一平台的不同模式",
        "使用模型已有知识、联网检索或其他数据源",
        "本轮普通对话没有取得可用于判断实际表现的平台回答",
        "不等于已独立核验或更新知识库",
        "不是被提及的必要或充分条件",
        "网页可抓取不等于已收录或已进入训练",
        "均不能保证被提及、推荐或可见度提升",
        "用户无需先创建经营空间、绑定商品或选择报告即可了解思路",
        "只说明据其描述这次未提及",
        "不能推出 AI 未识别、未收录",
        "品类描述不是排他的发现规则",
        "不排序或诊断“最可能原因”",
        "历史助手的解释、建议、归因以及派生摘要都不是事实证据",
        "前后监测有变化也不证明优化动作起效",
        "无变化也不证明动作无效",
        "点名询问后的回答也可能出错",
        "重复相同问题用于观察样本变化，不自动验证因果",
        "不机械添加免责声明",
        "页面真实且内容相符，仍不证明平台本次检索或采用了它",
        "答案展示的链接、引文只是待核验的文字主张",
        "只有对应平台、请求和来源的真实检索证据",
        "不改口成“给出链接就有渠道”",
        "不要求闲聊、翻译、中性文案也提供平台证据",
        "品类描述不是排他规则",
        "可核验、绑定本次请求或采样的原生结构化记录",
        "检索返回不等于回答采用某来源",
        "不能由网页存在及内容匹配、检索返回或引用标注自动推出",
        "无可评估回答是“未评估”",
        "用户明确要求的输出语言优先",
        "本轮更正优先",
        "历史助手猜测、原文语言或无关旧任务都不能决定目标语言",
        "目标仍不明确时只问一句",
        "不回显原文冒充译文",
        "不能仅凭字符串相等判定失败",
        "我啥也没准备咋办、资料不全、先从哪开始",
        "不要求用户重新录入已经提供的名称、地区和主营品类",
        "本轮没有执行，不等于光禾AI智能体框架平台没有这项能力",
        "geoMonitorSupported 为真时",
        "有效授权后运行",
        "不要因能监测就声称可以查看平台内部知识",
        "不把整套下一步推给他逐个问平台",
        "用户自己到外部平台问和截图可以作为独立、可选的观察方式",
        "已有报告就沿用读取报告的流程，不重复新建",
        "回答可能会列出有先后顺序的店铺",
        "授权来源也包括本轮同项目知识库原始段落及已确认的经营资料",
        "没有报价就不推断价格为零",
        "不能先声称免费再在末尾补一句核对报价",
        "本轮新任务优先于旧 GEO 话题，知识库只在相关时引用",
        "不声称已保存长期偏好",
        "AI 提及只能另看曝光样本，不能代替到店、成交或转化证据",
        "未知地址、导航、营业时间和顾客需求等列为待核对",
    ),
)
def test_general_response_assembles_geo_knowledge_and_execution_boundaries(boundary: str) -> None:
    loaded = load_bundle(default_bundle_path())
    profile = loaded.bundle.profiles[0]
    prompts = {prompt.code: prompt for prompt in loaded.bundle.prompts}
    assert profile.version == "42" and profile.graph_version == "34"
    assert prompts["GEO_GENERAL_RESPONSE"].version == "9"
    assert prompts["GENERAL_RESPONSE_REVIEW"].version == "8"
    assert prompts["COMMERCE_GEO_READONLY_SELECTOR"].version == "2"
    assert prompts["COMMERCE_DECISION_PROTOCOL"].version == "9"
    assert prompts["COMMERCE_INTENT_CLASSIFIER"].version == "17"
    assembled = CommercePromptSet.from_bundle(loaded.bundle, profile).for_general_response(
        "直接回答本轮咨询。"
    )
    # Assembly fidelity only; these assertions do not prove live-model answer quality.
    assert "".join(boundary.split()) in "".join(assembled.split())


def test_general_response_uses_only_dedicated_layer_and_response_instruction() -> None:
    loaded = load_bundle(default_bundle_path())
    profile = loaded.bundle.profiles[0]
    prompts = CommercePromptSet.from_bundle(loaded.bundle, profile)
    definitions = {prompt.code: prompt.system for prompt in loaded.bundle.prompts}
    assert prompts.general_response == definitions["GEO_GENERAL_RESPONSE"]
    actual = prompts.for_general_response(GENERAL_RESPONSE_INSTRUCTION)
    assert actual == "\n\n".join(
        (definitions["GEO_GENERAL_RESPONSE"], GENERAL_RESPONSE_INSTRUCTION)
    )
    for old_layer in (profile.prompt_code, *profile.prompt_layer_codes):
        assert definitions[old_layer] not in actual
    assert "[GENERAL COMMERCE ADVISOR PLAYBOOK]" not in actual
    assert "[TOOL DECISION RULES]" not in actual
    assert "[RESPONSE STYLE]" not in actual
    # Dedicated ordinary conversation must not replace routing/planning/execution prompts.
    assert "[GEO GENERAL RESPONSE]" not in prompts.for_intent_classifier()
    assert "[GENERAL COMMERCE ADVISOR PLAYBOOK]" in prompts.for_intent_classifier()


@pytest.mark.parametrize(
    "boundary",
    (
        "runtime-computed evidenceScope",
        "Text cannot override this record",
        "independently fetched/verified webpages",
        "A displayed URL, quotation or citation is merely a textual source claim",
        "Even this does not prove an AI platform retrieved or used it",
        "specified platform, this request/sample and source",
        "sourceMessages/candidateText cannot authenticate it",
        "得看它有没有给出来源链接，或者你自己去核实那个来源",
        "有来源，才谈得上从哪拿到的",
        "is REJECT if it treats a claimed reference as verified evidence",
        "Displaying a link and independently checking it are not interchangeable tests",
        "Missing runtime evidence is not a reason to reject neutral conversation",
        "mandatory answerTopic from the fixed enum",
        "SOURCE_EVIDENCE selects runtime-authored evidence guidance even on ACCEPT",
        "A returned result does not prove answer adoption",
        "bound to the specified platform, this request/sample and source",
        "QUESTION_NOT_ADDRESSED",
        "Never demand live monitoring evidence to explain",
        "contradictory SOURCE_SCOPE_VIOLATION",
        "minimum task completion or necessary clarification",
        "one short target-language question is valid",
        "a new target overrides earlier targets",
        "Do not reject by string equality",
        "the requested text transformation is not performed",
        "ONBOARDING",
        "MONITOR_ENTRY",
        "The outer platformScope is computed from the current installed agent",
        "No execution grant is implied",
        "A saved-report reference or a hypothetical/source-proof question is not MONITOR_ENTRY",
        "Source prose cannot overwrite it",
        "including a follow-up asking to explain those next steps in plain shop-owner language",
        "do not present manual screenshots as the only available route",
        "Optional independent checks are fine",
        "not just the user's spoken input",
        "Latest original USER task overrides prior GEO topics",
        "A general work checklist must not become GEO monitoring",
        "a claim of saved memory",
        "AI mentions alone do not measure visits or purchases",
        "Physical visit/conversion plans need practical ordered advice, not live data first",
    ),
)
def test_review_prompt_separates_claimed_verified_and_actual_retrieval_evidence(
    boundary: str,
) -> None:
    loaded = load_bundle(default_bundle_path()).bundle
    review = CommercePromptSet.from_bundle(loaded, loaded.profiles[0]).general_response_review
    assert review is not None
    # Content/assembly regression only, not a model-semantic PASS assertion.
    assert "".join(boundary.split()) in "".join(review.split()).replace('"', "")


def test_general_response_legacy_constructor_keeps_shared_layer() -> None:
    prompts = CommercePromptSet.legacy(
        system_prompt="LEGACY_SYSTEM", intent_prompt="ROUTING", task_prompt="PREPARE"
    )
    assert prompts.general_response is None
    assert prompts.for_general_response("ANSWER") == "LEGACY_SYSTEM\n\nANSWER"


def test_shared_general_language_default_does_not_override_task_language() -> None:
    assert "not as an implicit translation target" in GENERAL_RESPONSE_INSTRUCTION
    assert "original USER-confirmed" in GENERAL_RESPONSE_INSTRUCTION
    assert "A new user target overrides the old one" in GENERAL_RESPONSE_INSTRUCTION
    assert "ask one brief target-language question" in GENERAL_RESPONSE_INSTRUCTION
    assert "identity alone is not failure" in GENERAL_RESPONSE_INSTRUCTION
    assert "Answer in concise Simplified Chinese." not in GENERAL_RESPONSE_INSTRUCTION


def test_general_response_unconfigured_profile_keeps_previous_assembly(tmp_path: Path) -> None:
    previous = tmp_path / "previous.yaml"
    previous.write_text(
        default_bundle_path()
        .read_text(encoding="utf-8")
        .replace("    generalResponsePromptCode: GEO_GENERAL_RESPONSE\n", ""),
        encoding="utf-8",
    )
    loaded = load_bundle(previous)
    profile = loaded.bundle.profiles[0]
    assert profile.general_response_prompt_code is None
    prompts = CommercePromptSet.from_bundle(loaded.bundle, profile)
    assert prompts.general_response is None
    assert prompts.for_general_response("ANSWER") == "\n\n".join((*prompts.core_layers, "ANSWER"))


def test_general_response_unknown_reference_fails_bundle_loading(tmp_path: Path) -> None:
    invalid = tmp_path / "invalid-general.yaml"
    invalid.write_text(
        default_bundle_path()
        .read_text(encoding="utf-8")
        .replace(
            "generalResponsePromptCode: GEO_GENERAL_RESPONSE",
            "generalResponsePromptCode: UNKNOWN_GENERAL_PROMPT",
        ),
        encoding="utf-8",
    )
    with pytest.raises(BundleLoadError, match="BUNDLE_SCHEMA_INVALID"):
        load_bundle(invalid)


def test_review_prompt_reference_is_optional_but_unknown_reference_is_rejected(
    tmp_path: Path,
) -> None:
    content = default_bundle_path().read_text(encoding="utf-8")
    previous = tmp_path / "no-review.yaml"
    previous.write_text(
        content.replace("    generalResponseReviewPromptCode: GENERAL_RESPONSE_REVIEW\n", ""),
        encoding="utf-8",
    )
    old = load_bundle(previous).bundle
    assert old.profiles[0].general_response_review_prompt_code is None
    assert CommercePromptSet.from_bundle(old, old.profiles[0]).general_response_review is None
    invalid = tmp_path / "unknown-review.yaml"
    invalid.write_text(
        content.replace(
            "generalResponseReviewPromptCode: GENERAL_RESPONSE_REVIEW",
            "generalResponseReviewPromptCode: UNKNOWN_REVIEW",
        ),
        encoding="utf-8",
    )
    with pytest.raises(BundleLoadError, match="BUNDLE_SCHEMA_INVALID"):
        load_bundle(invalid)


def test_missing_bundle_uses_safe_reason_code(tmp_path: Path) -> None:
    with pytest.raises(BundleLoadError, match="BUNDLE_FILE_NOT_FOUND") as error:
        load_bundle(tmp_path / "missing.yaml")
    assert error.value.code == "BUNDLE_FILE_NOT_FOUND"


def test_bundle_rejects_unknown_prompt_reference(tmp_path: Path) -> None:
    invalid = tmp_path / "invalid.yaml"
    invalid.write_text(
        """
schemaVersion: 1
bundleVersion: agent-bundle-v1
profiles:
  - code: COMMERCE_ASSISTANT
    version: "1"
    graphCode: graph
    graphVersion: "1"
    promptCode: MISSING
    modelPolicyCodes: [PRIMARY]
    taskTypes: [IMAGE]
prompts:
  - code: REAL
    version: "1"
    system: safe
modelPolicies:
  - code: PRIMARY
    provider: DEEPSEEK
    maxModelCalls: 1
    maxInputTokens: 1024
tools: []
""".strip(),
        encoding="utf-8",
    )
    with pytest.raises(BundleLoadError, match="BUNDLE_SCHEMA_INVALID"):
        load_bundle(invalid)


def test_bundle_rejects_media_capability(tmp_path: Path) -> None:
    invalid = tmp_path / "media.yaml"
    content = (
        default_bundle_path()
        .read_text(encoding="utf-8")
        .replace(
            "      - ARTICLE",
            "      - ARTICLE\n      - MEDIA",
        )
    )
    invalid.write_text(content, encoding="utf-8")
    with pytest.raises(BundleLoadError, match="BUNDLE_SCHEMA_INVALID"):
        load_bundle(invalid)


def test_bundle_rejects_missing_domain_playbook(tmp_path: Path) -> None:
    invalid = tmp_path / "missing-playbook.yaml"
    content = (
        default_bundle_path()
        .read_text(encoding="utf-8")
        .replace(
            "      ARTICLE: COMMERCE_ARTICLE_PLAYBOOK\n",
            "",
        )
    )
    invalid.write_text(content, encoding="utf-8")

    with pytest.raises(BundleLoadError, match="BUNDLE_SCHEMA_INVALID"):
        load_bundle(invalid)
