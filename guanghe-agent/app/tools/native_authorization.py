"""A current-USER refusal fence, not an intent router or business authorization."""

import re

from app.agent.models import Intent
from app.model_gateway.gateway import intent_is_explicitly_negated, requests_domain_execution

USER_REQUEST_GUARDED_ACTIONS = frozenset(
    {"geo.monitor", "article.generate", "image.generate", "video.generate", "brand.save"}
)
_MONITOR = re.compile(
    r"(?:启动|执行|提交|开始|创建|发起|安排|进行|做).{0,8}(?:监测|检测)(?:任务)?|"
    r"(?:监测|检测)(?!报告|结果|数据|方案|情况|样本)|"
    r"(?:重跑|重新跑|再跑|跑)(?:一)?(?:次|遍|轮)|更新.{0,8}可见度"
)
_ARTICLE = re.compile(
    r"(?:生成|撰写|写|创作|制作|起草|输出).{0,100}"
    r"(?:文章|文案|稿件|软文|(?:一|1)?篇)"
)
_IMAGE_TARGET = re.compile(
    r"图片|照片|相片|插画|详情图|图稿|图标|图片素材|头像|壁纸|logo|贴纸|画面|"
    r"主图|海报|生图|商品图|配图|封面图|封面|效果图|图像|"
    r"(?:一|两|三|四|几|这|那|上|上一|当前|本)?张图(?!片)|"
    r"(?<![a-z])(?:image|photo)(?![a-z])"
)
_VIDEO_TARGET = re.compile(r"视频|短片|动画|(?<![a-z])video(?![a-z])")
_CREATIVE_ACTION = re.compile(
    r"重新生成|重新制作|生成|制作|创建|绘制|修改|调整|编辑|替换|剪辑|重做|重制|改|剪|做|出|画"
)
_IMAGE_DESIGN_ACTION = re.compile(r"设计")
_CREATIVE_REQUEST_PREFIX = re.compile(
    r"(?:(?:那就|那么|那|好的|好吧|好|嗯|行|可以|麻烦|请|帮我|帮我们|替我|给我|"
    r"为我|我要|我想要|我想|我需要|现在|今天|这次|先|再|直接|立即|马上|开始|重新|"
    r"继续|就|也|还|又|改为|我们要|我们想要|我们需要)+)?"
    r"(?:(?:按|根据|基于|针对|参考|沿用|使用|用(?!户)|把|将|给|为|替|帮).{0,100})?"
)
_CREATIVE_DIRECT_PREFIX = re.compile(
    r"(?:(?:那就|那么|那|好的|好吧|好|麻烦|请|现在|直接|立即|马上)+)?"
    r"(?:帮我|帮我们|替我|给我|给我们|为我|为我们|我要|我想要|我需要|来)"
    r"(?:一|两|三|四|几)?(?:个|段|条|支|部|张)?"
)
_CREATIVE_NON_COMMAND = re.compile(
    r"怎么|如何|怎样|能否|是否|是不是|有没有|能不能|可不可以|为什么|什么是|什么意思|"
    r"方法|教程|流程|步骤|费用|价格|多少钱|多少积分|收费|扣费|要花|耗费|需要多久|要多久|"
    r"建议|方向|方案|示例|原话|这句话|这段话|翻译|复述|客户说|用户说|我说|他说|她说|"
    r"对方说|老板说|支持吗|可以吗|行不行|(?:吗|么|嘛)$"
)
_CREATIVE_CONDITION = re.compile(
    r"如果|假如|假设|比如|例如|等我确认|确认后|稍后|以后|下次|"
    r"等(?:我|你|资料|素材|预算|额度|积分|确认|准备|配置).{0,20}(?:再|后)|"
    r"(?:预算|额度|积分|资料|素材).{0,12}"
    r"(?:足够|充足|允许|齐全|完备|配好|配置好).{0,8}(?:就|再)"
)
_CREATIVE_CONDITIONAL_SENTENCE = re.compile(
    r"如果|假如|假设|比如|例如|等我确认|确认后|"
    r"等(?:我|你|资料|素材|预算|额度|积分|确认|准备|配置).{0,20}(?:再|后)|"
    r"(?:预算|额度|积分|资料|素材).{0,12}(?:足够|充足|允许|齐全|完备|配好|配置好)"
    r".{0,8}(?:就|再)"
)
_CREATIVE_STATUS = re.compile(
    r"已经|已(?:生成|制作|修改|完成|成功|失败|排队|提交|受理|出图)|"
    r"正在|刚刚|刚才|昨天|上次|之前|完成|成功|失败|超时|排队|进行中|进度|状态|"
    r"生成了|制作了|修改了|做好了|改好了|剪好了|出来了|生成了吗|制作了吗|修改了吗|"
    r"做好了吗|改好了吗|剪好了吗|出来了吗"
)
_CREATIVE_IMPERATIVE = re.compile(
    r"请|帮我|帮我们|替我|给我|为我|我要|我想要|我需要|麻烦|现在|直接|立即|马上|"
    r"开始|重新|再|把|将|改为|按|根据|基于|针对|参考|使用|用"
)
_PREFIXED_DRAW_REQUEST = re.compile(
    r"^(?:(?:那就|那么|那|好的|好吧|好|麻烦|请|现在|直接|立即|马上|重新|再)?"
    r"(?:帮我|帮我们|替我|给我|给我们|为我|为我们|我要|我想要|我需要)|"
    r"(?:麻烦|请))"
    r"(?:画|绘制)(?!得|过|了|完|好)[^，。；,;!?\n]+$"
)
_QUANTIFIED_DRAW_REQUEST = re.compile(
    r"^(?:画|绘制)(?:一|两|三|四|几)(?:张|幅|个|只)?[^，。；,;!?\n]+$"
)
_DRAW_DECLARATIVE = re.compile(
    r"是|用于|需要|的人|的工作|的工具|来自|属于|很(?:好|差|难)|不错|专业|耗时"
)
_BARE_CREATIVE_COMMAND_TAIL = re.compile(r"^(?:吧|一下)?$")
_CREATIVE_REVERSE_COMMAND = re.compile(r"帮我|帮我们|替我|给我|给我们|为我|为我们|请")
_CREATIVE_EVALUATION_TAIL = re.compile(
    r"^(?:(?:效果|质量|画质|设计|做工|成品).{0,8})?(?:很好|不错|专业|优秀|一般|很差|不好)"
)
_CREATIVE_STATEMENT_PREFIX = re.compile(r"^(?:来自|来源|出自|出处|出色|做工)")
_CREATIVE_NON_DELIVERY_TAIL = re.compile(
    r"^(?:的)?(?:工具|人|团队|工作|功能|模型|软件|系统|教程|方法|流程|技术|作者|公司|"
    r"服务|平台|链接|地址|网址|编号|id|说明|分析|报告|报价|播放器|评价|状态|进度|"
    r"结果|来源|出处)|"
    r"^(?:重新生成|重新制作|生成|制作|创建|绘制|修改|调整|编辑|替换|剪辑|重做|"
    r"重制|改|剪|做|出|画)(?:的)?(?:工具|人|团队|工作|功能|模型|软件|系统|教程|"
    r"方法|流程|技术|作者|公司|服务|平台)|^(?:看看|看一下)|"
    r"(?:坏了|离职了|很专业|耗时|来自|属于|是我(?:的)?工作|需要技术)"
)
_CREATIVE_RELATIONAL_STATEMENT = re.compile(
    r"的(?:工具|人|团队|工作|功能|模型|软件|系统|教程|方法|流程|技术|作者|公司|服务|平台)|"
    r"(?:需要技术|坏了|离职了|来自|属于|是我(?:的)?工作)"
)
_CREATIVE_EVALUATIVE_STATEMENT = re.compile(
    r"(?:效果|质量|画质|设计|做工|成品).{0,8}(?:很好|不错|专业|优秀|一般|很差|不好)"
)
_CREATIVE_PROPOSAL_EVALUATION = re.compile(
    r"(?:(?:(?:我觉得|我感觉|感觉|这样|这么做)?(?:会|应该|可能|或许)?"
    r"(?:比较|更|挺|很|还)?(?:好|不错|合适|可行|可以)(?:一点|一些|些|的)?"
    r"(?:呀|啊|呢|吧)?|(?:值得(?:尝试|试试|考虑)|可以考虑)(?:一下|的)?"
    r"(?:呀|啊|呢|吧)?|(?:是|算是|是个)(?:好主意|好方案|建议)|"
    r"方案(?:很)?(?:好|不错|可行)|(?:好吗|好不好|怎么样|你觉得呢|你看呢))"
    r"(?:[?\uff1f。.!\uff01…])*$)"
)
_CREATIVE_DISCUSSION_OR_SUGGESTION = re.compile(
    r"(?:我(?:觉得|认为|建议|推荐|倾向)|你(?:觉得|认为|看)|不妨|何不|要不(?:要)?|是否应该)|"
    r"(?:这样|这么做|这个方案|这个思路).{0,16}(?:行不行|可不可以|合不合适|"
    r"行吗|可以吗|合适吗)|(?:行不行|可不可以|合不合适|要不要).{0,16}(?:这样|这么做|试试)|"
    r"(?:行吗|可以吗|合适吗)"
)
_ATTACHMENT_IMAGE_EDIT_TARGET = re.compile(
    r"背景|颜色|色调|文字|水印|尺寸|比例|主体|人物|商品|画面|风格|清晰度|构图|"
    r"脸|面部|痕迹|红痕|伤痕|疤痕|划痕|标记|"
    r"去掉|移除|添加|加上|涂|画上|扩图|抠图|修图|美化|裁剪|裁成"
)
_ATTACHMENT_VIDEO_EDIT_TARGET = re.compile(
    r"视频|短片|动画|动效|动态图|动态效果|做成|转成|变成|制作成|动起来|动一动|"
    r"眨眼|挥手|冒(?:出)?热气|镜头(?:向前)?推进"
)
_PREVIOUS_REFERENCE_PREFIX = (
    r"(?:刚才(?:生成|制作|做|画)?(?:的)?(?:那|这)?(?:一)?张|(?:上|上一|前一)张)"
)
_PREVIOUS_ITEM_REFERENCE = re.compile(_PREVIOUS_REFERENCE_PREFIX)
_PREVIOUS_IMAGE_NOUN = (
    r"(?:图片素材|图片|图像|照片|相片|海报|封面图|封面|配图|插画|主图|详情图|"
    r"商品图|效果图|图稿|图标|头像|壁纸|logo|贴纸|画面|图(?!表|纸))"
)
_PREVIOUS_OMITTED_IMAGE_TARGET = (
    r"(?:背景|颜色|色调|文字|水印|尺寸|比例|主体|人物|商品|画面|风格|清晰度|构图|"
    r"去掉|移除|添加|加上|扩图|抠图|修图|美化|裁剪|裁成|改|修改|编辑|调整|重绘|"
    r"重做|替换|换成|变成|做成|转成|制作成|"
    r"动起来|动一动|眨眼|挥手|冒(?:出)?热气|镜头(?:向前)?推进)"
)
_PREVIOUS_IMAGE_REFERENCE = re.compile(
    rf"{_PREVIOUS_REFERENCE_PREFIX}(?:{_PREVIOUS_IMAGE_NOUN}|(?=(?:的)?{_PREVIOUS_OMITTED_IMAGE_TARGET}))"
)
_CREATIVE_READ_ONLY_LEAD = re.compile(
    r"^(?:(?:请|麻烦|帮我|帮我们|给我|给我们|替我|为我|为我们|现在))*"
    r"(?:分析|评价|点评|介绍|审查|审核|研究|学习|讨论|看看|看一下|复盘|比较|说明)"
)
_CONTROLLED_COLOR_VALUE = (
    r"(?:(?:深|浅|亮|暗|墨|藏|天|湖|玫瑰|荧光|奶油)?"
    r"(?:红|橙|黄|绿|青|蓝|紫|粉|黑|白|灰|棕|金|银|咖啡|米白)色|"
    r"#[0-9a-f]{3,8}|rgba?\((?:\d{1,3},){2}\d{1,3}(?:,(?:0|1|0?\.\d+))?\))"
)
_PREVIOUS_IMAGE_COLOR_EDIT = re.compile(
    rf"^(?:(?:请|麻烦|帮我|给我|替我|直接|现在|立即|马上))*"
    rf"(?:把|将)?{_PREVIOUS_REFERENCE_PREFIX}(?:{_PREVIOUS_IMAGE_NOUN})?(?:的)?(?:颜色)?"
    rf"(?:改成|换成|变成){_CONTROLLED_COLOR_VALUE}(?:吧|一下)?$"
)
_ATTACHMENT_MARK_EDIT = re.compile(
    r"^(?:(?:请|麻烦|帮我|给我|替我|直接|现在|立即|马上))*"
    r"(?=.{1,100}$)(?=.*(?:人物|人脸|脸|面部))"
    r"(?=.*(?:涂|画|加)(?:上|一道|一条|一个)?.{0,20}"
    r"(?:痕迹|红痕|伤痕|疤痕|划痕|标记)).+$"
)
_ATTACHMENT_MARK_COMMAND_LEAD = re.compile(
    r"^(?:(?:你|请|麻烦|帮我|给我|替我|直接|现在|立即|马上))*"
    r"(?:把|将|给(?:这张|那张|上图|原图|附件|人物|人脸|脸|面部))"
)
_PREVIOUS_USER_UPLOAD_REFERENCE = (
    r"(?:(?:我(?:刚才|前面|之前|上(?:一)?轮)|(?:刚才|前面|之前|上(?:一)?轮)(?:我)?)"
    r"(?:上传|发(?:送)?|提供|给)(?:的)?"
    r"(?:那|这)?(?:一)?张?(?:图片|图像|照片|相片|图))"
)
_PREVIOUS_USER_ATTACHMENT_REQUEST = re.compile(
    r"^(?:(?:你|请|麻烦|帮我|给我|替我|直接|现在|立即|马上))*"
    rf"(?:(?:按|根据|基于|参考|沿用|使用|用)(?:{_PREVIOUS_USER_UPLOAD_REFERENCE}|"
    r"(?:这|那)(?:一)?张?(?:图片|图像|照片|相片|图))"
    r"(?:(?:作为|作)?参考)?.{0,24}"
    r"(?:重新生成|生成|重做|制作|设计|创作|继续(?:修改|编辑|调整)?|"
    r"加深|改深|修改|编辑|调整|重绘).{0,30}"
    rf"|(?:把|将){_PREVIOUS_USER_UPLOAD_REFERENCE}.{{0,20}}"
    r"(?:继续)?(?:修改|编辑|调整|重绘|重做|加深|改深).{0,30})$"
)
_PREVIOUS_USER_ATTACHMENT_VIDEO_REQUEST = re.compile(
    r"^(?:(?:你|请|麻烦|帮我|给我|替我|直接|现在|立即|马上))*"
    rf"(?:(?:让|使){_PREVIOUS_USER_UPLOAD_REFERENCE}.{{0,16}}"
    r"(?:动起来|动一动|眨眼|挥手|冒(?:出)?热气|(?:镜头)?(?:向前)?推进)"
    rf"|(?:把|将){_PREVIOUS_USER_UPLOAD_REFERENCE}.{{0,20}}"
    r"(?:做成|转成|变成|制作成).{0,16}(?:视频|短片|动画|动效|动态图|动态效果))$"
)
_ATTACHMENT_IMAGE_EDIT = re.compile(
    r"^(?:(?:请|麻烦|帮我|给我|替我|直接|现在|立即|马上))*"
    r"(?:(?:把|将)?(?:这张|那张|上图|原图|附件|它|这个|刚才.{0,8}张|(?:上|上一|前一)张)?.{0,16}"
    r"(?:改|修改|编辑|调整|重绘|重做|替换|换成|去掉|移除|添加|加上|变成|"
    r"扩图|抠图|修图|美化|裁剪|裁成).{0,24}"
    r"(?:背景|颜色|色调|文字|水印|尺寸|比例|主体|人物|商品|画面|风格|清晰度|构图)"
    r"|(?:把|将)?(?:这张|那张|上图|原图|附件|它|这个|刚才.{0,8}张|(?:上|上一|前一)张)?.{0,8}"
    r"(?:背景|颜色|色调|文字|水印|尺寸|比例|主体|人物|商品|画面|风格|清晰度|构图)"
    r".{0,8}(?:改|修改|编辑|调整|重绘|重做|替换|换成|去掉|移除|添加|加上|变成|"
    r"扩图|抠图|修图|美化|裁剪|裁成).{0,24}"
    r"|(?:去掉|移除|替换|调整|修改|裁剪|裁成|扩图|抠图|修图|美化).{0,24}"
    r"|(?:再\s*)?(?:添加|加上)\s*\S.{0,23})$"
)
_ATTACHMENT_VIDEO_EDIT = re.compile(
    r"^(?:(?:请|麻烦|帮我|给我|替我|直接|现在|立即|马上))*"
    r"(?:(?:把|将)?(?:这张|那张|上图|原图|附件|它|这个|刚才.{0,8}张|(?:上|上一|前一)张).{0,24}"
    r"(?:做成|转成|变成|制作成).{0,20}(?:视频|短片|动画|动效|动态图|动态效果)"
    r"|(?:让|使)(?:这张|那张|上图|原图|附件|它|这个|图中主体|主体|人物|画面|镜头|"
    r"刚才.{0,8}张|(?:上|上一|前一)张)?.{0,12}"
    r"(?:动起来|动一动|眨眼|挥手|冒(?:出)?热气|(?:镜头)?(?:向前)?推进)"
    r"|(?:把|将|给|为)?(?:这张|那张|上图|原图|附件|它|这个|刚才.{0,8}张|(?:上|上一|前一)张)"
    r".{0,16}(?:加上|添加|加|做成|变成)?动态效果)$"
)
_IMAGE_TO_VIDEO = re.compile(
    r"^(?:(?:请|麻烦|帮我|给我|替我|直接|现在|立即|马上))*"
    r"(?:把|将)?(?:这张|那张|上图|原图|附件|它|这个|图片|图|刚才.{0,8}张|(?:上|上一|前一)张).{0,24}"
    r"(?:做成|转成|变成|制作成).{0,20}(?:视频|短片|动画|动效|动态图|动态效果)$"
)
_CREATIVE_NEGATION = r"(?:不要|不用|不需要|无需|先不|暂不|别|取消|停止|不想|不再|不必|不)"
_CREATIVE_CONFIRMATION_REFUSAL = re.compile(
    r"(?:不要|不用|无需|不需要)(?:再)?(?:问|确认|解释|说明)(?:我)?"
)
_ACTION_CLAUSE_SEPARATOR = re.compile(
    r"[，。；,;!?\uff01\uff1f\n]|然后|并且|同时|接着|但是|而是|但|"
    r"只(?=(?:生成|设计|制作|创建|绘制|修改|调整|编辑|替换|剪辑|重做|重制|改|剪|做|出|画))"
)
_CREATIVE_SENTENCE_SEPARATOR = re.compile(r"[。；;!?\uff01\uff1f\n]")
_CONSULTATION = re.compile(r"怎么|如何|怎样|能否|是否|解释|了解|讲讲|方法|教程|流程|费用|价格")
_NEGATION = re.compile(
    r"不要|不用|不需要|无需|先不|暂不|别|取消|停止|不想|"
    r"不(?:保存|记录|设置|更新|修改|更正|生成|写|启动|执行|提交|创建|监测|检测)"
)
_MONITOR_REQUEST_PREFIX = re.compile(
    r"(?:(?:那就|那么|那|好的|好吧|好|嗯|行|可以|麻烦|请|帮我|帮我们|替我|给我|"
    r"为我|我要|我想要|我想|我需要|现在|今天|这次|这轮|先|再|直接|立即|马上|"
    r"重新|继续|就|也|还|又)+)?"
    r"(?:(?:按|根据|基于|针对|对|用|在|把|给|为|替|帮).{1,100})?"
)
_MONITOR_NON_COMMAND = re.compile(
    r"怎么|如何|怎样|能否|是否|是不是|有没有|能不能|可不可以|为什么|什么|为何|"
    r"解释|了解|讲讲|方法|教程|流程|"
    r"费用|价格|多少钱|多少积分|多少点|收费|扣费|要花|耗费|需要多久|要多久|"
    r"建议|示例|原话|这句话|这段话|翻译|复述|改写|你说|他说|她说|客户说|花了|扣了"
)
_MONITOR_CONDITION = re.compile(
    r"如果|假如|假设|比如|例如|等我确认|确认后|没有报告时|等.{0,20}(?:再|后)|"
    r"(?:预算|额度|积分|资料).{0,12}(?:足够|充足|允许|齐全|完备|配好|配置好).{0,8}(?:就|再)"
)
_ARTICLE_CONTINUE_CONFIRMATION = re.compile(
    r"(?:好[的吧]?，?)?(?:那就|请|现在|直接)?继续(?:做|写|生成)?(?:吧)?"
)


def is_article_offer_confirmation(content: str) -> bool:
    """A bounded confirmation candidate, never authority without Java's offer.

    Keep normalization aligned with Java: preserve interior whitespace, normalize
    commas and ignore sentence-ending stops/exclamations, not question marks.
    """
    if len(content) > 40:
        return False
    normalized = content.strip().rstrip(".。!\uff01").replace(",", "，")
    # A continuation word only selects Java's scoped offer check. It never opens
    # a task on its own, including when an earlier message merely discussed GEO.
    return bool(_ARTICLE_CONTINUE_CONFIRMATION.fullmatch(normalized)) or normalized in {
        "好",
        "好的",
        "可以",
        "行",
        "没问题",
        "认可",
        "同意",
        "确认",
        "就按这个写",
        "按这个写",
        "开始写",
        "直接写",
        "写吧",
        "生成吧",
        "开始生成",
        "就这样写",
        "可以写",
        "可以生成",
        "好，写吧",
        "好的，写吧",
        "好，就按这个写",
    }


def _explicit_monitor_request(content: str) -> bool:
    """Recognize a current USER command without the legacy router's shortcuts.

    This only makes the monitoring tool available; it does not submit a task or
    authorize a budget. Keep consultation, examples and conditional future work
    read-only, including when their condition is separated by a comma.
    """
    text = _strip_non_authoritative_examples(content)
    text = "".join(text.casefold().split())
    if _MONITOR_CONDITION.search(text):
        return False
    # An explicit stop/read-only instruction in the same message wins, even if
    # a quoted earlier command or another clause looks imperative.
    if re.search(
        r"(?:不要|不用|不需要|无需|先不|暂不|别|取消|停止|不想|不)[^，。；,;!?\uff01\uff1f]{0,12}"
        r"(?:监测|检测|执行|启动|提交|任务)|仅(?:解释|分析|查看)|只(?:解释|分析|查看|给方案|给计划)",
        text,
    ):
        return False
    for clause in filter(
        None, re.split(r"[，。；,;!?\uff01\uff1f\n]|然后|并且|同时|接着|但是|而是", text)
    ):
        if _MONITOR_NON_COMMAND.search(clause):
            continue
        for match in _MONITOR.finditer(clause):
            prefix, suffix = clause[: match.start()], clause[match.end() :]
            if not _MONITOR_REQUEST_PREFIX.fullmatch(prefix) or _NEGATION.search(prefix):
                continue
            if re.search(
                r"^(?:的)?(?:方法|步骤|教程|流程|技巧|费用|价格|功能|按钮|入口|页面|字段)|"
                r"^(?:任务)?(?:已|已经|正在|还在|尚未|仍未|还没|尚没|未|没|没有)|"
                r"^(?:任务)?(?:完成|结束|成功|失败|超时|异常|中断|报错|排队|进行中)|"
                r"是什么|什么意思|(?:已|已经|有没有)?(?:完成|结束|成功|失败|启动|提交)了|"
                r"(?:了吗|了没|过了|过吗|过没|的吗)$",
                suffix,
            ):
                continue
            # A domain-free repeat command remains supported, but must not turn
            # "再跑一次文章生成" into a monitoring request.
            if re.fullmatch(
                r"(?:重跑|重新跑|再跑|跑)(?:一)?(?:次|遍|轮)", match.group()
            ) and not re.fullmatch(r"(?:监测|检测)?(?:吧|一下|就行|就可以了)?", suffix):
                continue
            return True
    return False


def _strip_non_authoritative_examples(content: str) -> str:
    """Remove quoted/code examples before deriving current-turn authority."""

    text = re.sub(r"(?m)^\s*>.*$", "", content)
    return re.sub(
        r"```[\s\S]*?```|`[^`]*`|“[^”]*”|\u2018[^\u2019]*\u2019|\"[^\"]*\"|"
        r"'[^']*'|「[^」]*」|『[^』]*』",
        "",
        text,
    )


def _creative_clause_is_negated(clause: str, target: re.Pattern[str]) -> bool:
    # "不用问我，直接生成图片" is an execution request: the refusal governs
    # confirmation, not creation. Strip only these bounded phrases before the
    # proximity checks below.
    normalized = _CREATIVE_CONFIRMATION_REFUSAL.sub("", clause)
    action = _CREATIVE_ACTION.pattern
    domain = target.pattern
    return bool(
        re.search(rf"{_CREATIVE_NEGATION}.{{0,8}}(?:{action}|{domain})", normalized)
        or re.search(rf"(?:{domain}).{{0,8}}{_CREATIVE_NEGATION}(?:.{{0,4}}{action})?", normalized)
    )


def _explicit_creative_request(clause: str, target: re.Pattern[str]) -> bool:
    """Recognize one explicit image/video create-or-edit command conservatively."""

    clause = _CREATIVE_CONFIRMATION_REFUSAL.sub("", clause)
    if (
        _CREATIVE_CONDITION.search(clause)
        or _CREATIVE_NON_COMMAND.search(clause)
        or _creative_clause_is_negated(clause, target)
    ):
        return False
    targets = tuple(target.finditer(clause))
    if not targets:
        return False
    if _CREATIVE_STATUS.search(clause) and not _CREATIVE_IMPERATIVE.search(clause):
        return False

    for action in _CREATIVE_ACTION.finditer(clause):
        for domain in targets:
            if max(action.start(), domain.start()) - min(action.end(), domain.end()) > 64:
                continue
            if action.start() <= domain.start():
                prefix = clause[: action.start()]
                tail = clause[domain.end() :]
                if not _CREATIVE_REQUEST_PREFIX.fullmatch(prefix):
                    continue
                if _CREATIVE_NON_DELIVERY_TAIL.search(tail):
                    continue
                # Without a request prefix, only a concise delivery command is
                # authoritative.  This keeps natural imperatives such as
                # "生成春日海报" while rejecting declarative phrases such as
                # "生成图片的工具坏了" or "做图片需要技术".
                if not prefix and not _BARE_CREATIVE_COMMAND_TAIL.fullmatch(tail):
                    continue
                if not prefix and (
                    _CREATIVE_STATEMENT_PREFIX.match(clause)
                    or _CREATIVE_EVALUATION_TAIL.match(tail)
                ):
                    continue
                return True

            prefix = clause[: domain.start()]
            between = clause[domain.end() : action.start()]
            tail = clause[action.end() :]
            if _CREATIVE_NON_DELIVERY_TAIL.search(tail):
                continue
            if _CREATIVE_REQUEST_PREFIX.fullmatch(prefix) and (
                prefix or _CREATIVE_REVERSE_COMMAND.search(between)
            ):
                return True

    # "给我一张商品图" and "我要一个视频" are direct creation requests
    # even when the user omits the generation verb. A bare domain noun is not.
    for domain in targets:
        prefix = clause[: domain.start()]
        tail = clause[domain.end() :]
        if _CREATIVE_DIRECT_PREFIX.fullmatch(prefix) and not _CREATIVE_NON_DELIVERY_TAIL.search(
            tail
        ):
            return True
    return clause in {"生图", "生张图"}


def _explicit_direct_draw_request(clause: str) -> bool:
    """Allow a bounded drawing imperative even when the user omits 图/图片."""

    clause = _CREATIVE_CONFIRMATION_REFUSAL.sub("", clause)
    if (
        _CREATIVE_CONDITION.search(clause)
        or _CREATIVE_NON_COMMAND.search(clause)
        or _CREATIVE_STATUS.search(clause)
        or re.search(rf"{_CREATIVE_NEGATION}.{{0,8}}(?:画|绘制)", clause)
    ):
        return False
    return bool(
        (_PREFIXED_DRAW_REQUEST.fullmatch(clause) or _QUANTIFIED_DRAW_REQUEST.fullmatch(clause))
        and not _DRAW_DECLARATIVE.search(clause)
    )


def _explicit_image_design_request(clause: str) -> bool:
    """Allow design only as a verb before an image deliverable, never as a noun."""

    clause = _CREATIVE_CONFIRMATION_REFUSAL.sub("", clause)
    if (
        _CREATIVE_CONDITION.search(clause)
        or _CREATIVE_NON_COMMAND.search(clause)
        or _creative_clause_is_negated(clause, _IMAGE_TARGET)
        or re.search(rf"{_CREATIVE_NEGATION}.{{0,8}}设计", clause)
    ):
        return False
    for action in _IMAGE_DESIGN_ACTION.finditer(clause):
        for target in _IMAGE_TARGET.finditer(clause, action.end()):
            if target.start() - action.end() > 64:
                continue
            prefix = clause[: action.start()]
            tail = clause[target.end() :]
            if not _CREATIVE_REQUEST_PREFIX.fullmatch(prefix):
                continue
            if _CREATIVE_NON_DELIVERY_TAIL.search(tail):
                continue
            if not prefix and not _BARE_CREATIVE_COMMAND_TAIL.fullmatch(tail):
                continue
            return True
    return False


def _explicit_attachment_image_edit(clause: str) -> bool:
    """Accept an attached-image command, not a question or statement about editing."""

    structured_mark_edit = bool(
        _ATTACHMENT_MARK_EDIT.fullmatch(clause) and _ATTACHMENT_MARK_COMMAND_LEAD.search(clause)
    )
    if not (
        _ATTACHMENT_IMAGE_EDIT.fullmatch(clause)
        or structured_mark_edit
        or _PREVIOUS_IMAGE_COLOR_EDIT.fullmatch(clause)
        or _PREVIOUS_USER_ATTACHMENT_REQUEST.fullmatch(clause)
    ):
        return False
    if (
        _CREATIVE_READ_ONLY_LEAD.search(clause)
        or _has_untrusted_previous_item_reference(clause)
        or _CREATIVE_CONDITION.search(clause)
        or _CREATIVE_NON_COMMAND.search(clause)
        or _creative_clause_is_negated(clause, _ATTACHMENT_IMAGE_EDIT_TARGET)
        or (_CREATIVE_RELATIONAL_STATEMENT.search(clause) and not structured_mark_edit)
        or _CREATIVE_EVALUATIVE_STATEMENT.search(clause)
    ):
        return False
    return not (_CREATIVE_STATUS.search(clause) and not _CREATIVE_IMPERATIVE.search(clause))


def _explicit_attachment_video_edit(clause: str) -> bool:
    """Accept an attached-media animation command, never a negative or discussion."""

    previous_user_attachment_request = bool(
        _PREVIOUS_USER_ATTACHMENT_VIDEO_REQUEST.fullmatch(clause)
    )
    if not (
        _ATTACHMENT_VIDEO_EDIT.fullmatch(clause)
        or _IMAGE_TO_VIDEO.fullmatch(clause)
        or previous_user_attachment_request
    ):
        return False
    if (
        _CREATIVE_READ_ONLY_LEAD.search(clause)
        or _has_untrusted_previous_item_reference(clause)
        or _CREATIVE_CONDITION.search(clause)
        or _CREATIVE_NON_COMMAND.search(clause)
        or _creative_clause_is_negated(clause, _ATTACHMENT_VIDEO_EDIT_TARGET)
        or _CREATIVE_RELATIONAL_STATEMENT.search(clause)
        or _CREATIVE_EVALUATIVE_STATEMENT.search(clause)
    ):
        return False
    return not (
        _CREATIVE_STATUS.search(clause)
        and not _CREATIVE_IMPERATIVE.search(clause)
        and not previous_user_attachment_request
    )


def _has_untrusted_previous_item_reference(clause: str) -> bool:
    """Reject non-image prior items even when the current turn has attachments."""

    return bool(_PREVIOUS_ITEM_REFERENCE.search(clause)) and not bool(
        _PREVIOUS_IMAGE_REFERENCE.search(clause)
    )


def user_requested_native_actions(
    content: str,
    *,
    brand_name_question: str | None = None,
    has_current_attachments: bool = False,
) -> frozenset[str]:
    """Freeze only specific commands in the original USER before observing tools.

    Quoted/code examples, conditions and consultation are not execution requests.
    Monitoring has its own command fence, not the legacy router's read-shortcut
    predicate. Article and brand behavior remain unchanged.
    """
    text = _strip_non_authoritative_examples(content)
    text = "".join(text.casefold().split())
    clauses = tuple(filter(None, _ACTION_CLAUSE_SEPARATOR.split(text)))
    allowed: set[str] = set()
    if _explicit_monitor_request(content):
        allowed.add("geo.monitor")
    if _answers_brand_name_question(content, brand_name_question):
        allowed.add("brand.save")
    # A trailing proposal/evaluation governs the whole creative sentence.  Do
    # this before splitting punctuation; otherwise a statement such as
    # "生成一版，可以考虑" loses its non-imperative tail and the first clause
    # can accidentally authorize a paid provider call.
    candidate_creative_clauses = tuple(
        clause
        for sentence in filter(None, _CREATIVE_SENTENCE_SEPARATOR.split(text))
        if not _CREATIVE_CONDITIONAL_SENTENCE.search(sentence)
        for clause in filter(None, _ACTION_CLAUSE_SEPARATOR.split(sentence))
    )
    # Let a later explicit imperative override an earlier observation (for
    # example "我觉得太浅了，请重新生成").  Conversely, if the user ends on
    # a proposal or asks for an opinion, the turn is discussion-only and must
    # not authorize a paid provider call.
    creative_clauses = (
        ()
        if candidate_creative_clauses
        and (
            _CREATIVE_PROPOSAL_EVALUATION.search(candidate_creative_clauses[-1])
            or _CREATIVE_DISCUSSION_OR_SUGGESTION.search(candidate_creative_clauses[-1])
        )
        else candidate_creative_clauses
    )
    for clause in creative_clauses:
        if _CREATIVE_PROPOSAL_EVALUATION.search(
            clause
        ) or _CREATIVE_DISCUSSION_OR_SUGGESTION.search(clause):
            continue
        if _has_untrusted_previous_item_reference(clause):
            continue
        references_available = (
            has_current_attachments
            or bool(_PREVIOUS_IMAGE_REFERENCE.search(clause))
            or bool(_PREVIOUS_USER_ATTACHMENT_REQUEST.fullmatch(clause))
            or bool(_PREVIOUS_USER_ATTACHMENT_VIDEO_REQUEST.fullmatch(clause))
        )
        if _IMAGE_TO_VIDEO.fullmatch(clause) or _PREVIOUS_USER_ATTACHMENT_VIDEO_REQUEST.fullmatch(
            clause
        ):
            if references_available and _explicit_attachment_video_edit(clause):
                allowed.add("video.generate")
            continue
        attachment_video_edit = bool(
            references_available and _explicit_attachment_video_edit(clause)
        )
        if not attachment_video_edit and (
            _explicit_creative_request(clause, _IMAGE_TARGET)
            or _explicit_image_design_request(clause)
            or _explicit_direct_draw_request(clause)
            or (references_available and _explicit_attachment_image_edit(clause))
        ):
            allowed.add("image.generate")
        if _explicit_creative_request(clause, _VIDEO_TARGET) or attachment_video_edit:
            allowed.add("video.generate")
    for clause in clauses:
        if re.search(r"如果|假如|假设|比如|例如|等我确认|确认后|没有报告时", clause):
            continue
        for action, intent, pattern in (("article.generate", Intent.ARTICLE, _ARTICLE),):
            if intent_is_explicitly_negated(clause, intent):
                continue
            for match in pattern.finditer(clause):
                prefix = clause[: match.start()]
                if _NEGATION.search(prefix):
                    continue
                # The latest imperative may follow an introductory question:
                # "报告怎么回事，帮我写文章" is handled as two clauses.
                imperatives = tuple(re.finditer(r"帮我|替我|给我|请|我要|现在|直接|立即", prefix))
                governed = prefix[imperatives[-1].start() :] if imperatives else prefix
                if _CONSULTATION.search(governed) or re.search(
                    r"^(?:的)?(?:方法|步骤|教程|流程|技巧|费用|价格)|是什么|什么意思",
                    clause[match.end() :],
                ):
                    continue
                ordinary_command = requests_domain_execution(clause)
                # Compact mixed command without a conjunction: "联网查资料写篇文章".
                # This exception never opens monitoring or a quoted how-to request.
                research_then_write = action == "article.generate" and bool(
                    re.fullmatch(
                        r"(?:先)?(?:联网|上网)?(?:查|查找|查询|搜索|搜集|收集).*(?:资料|信息|材料|结果)",
                        prefix,
                    )
                )
                based_on_material = action == "article.generate" and bool(
                    re.fullmatch(r"(?:根据|基于|围绕|结合|按照|按).{1,100}", prefix)
                )
                if ordinary_command or research_then_write or based_on_material:
                    allowed.add(action)
        # Naming a brand as a search target is not a request to create/change it.
        # Actual mutation still has Java's exact-current-USER name/alias checks.
        if (
            not _NEGATION.search(clause)
            and not _CONSULTATION.search(clause)
            and (
                re.search(
                    r"(?:记住|保存|记录|设置|更新|修改|更正|改为|改成).{0,16}(?:品牌|店名|公司名|名称)",
                    clause,
                )
                or re.search(
                    r"(?:品牌|店名|公司名|名称).{0,16}(?:记住|保存|记录|设置|更新|修改|更正|改成)",
                    clause,
                )
                or re.search(r"(?:我的|我们的|本店的)?(?:品牌|店名|公司名)(?:叫|是|名称是)", clause)
                or re.search(
                    r"^(?:我(?:的)?|我们(?:的)?)(?:客户|咖啡店|店铺|门店|公司|餐厅)(?:叫做|叫|是)",
                    clause,
                )
                or _names_brand_in_optimization_request(clause)
            )
        ):
            allowed.add("brand.save")
    return frozenset(allowed)


def _names_brand_in_optimization_request(clause: str) -> bool:
    match = re.fullmatch(
        r"(?:请)?(?:帮|给|为)(?:我的客户|我的品牌|客户|品牌)?"
        r"(?P<name>[^，。；!?\uff01\uff1f]{1,100})(?:进行|做)(?:geo|品牌geo)优化",
        clause,
    )
    return match is not None and not re.fullmatch(
        r"(?:我(?:们)?(?:的)?|这|这个|该)?(?:品牌|客户|公司|店铺|门店|咖啡店)?",
        match.group("name"),
    )


def _answers_brand_name_question(content: str, question: str | None) -> bool:
    """A short current USER answer to the immediately preceding naming question.

    Only brand naming is opened. Historical questions never enable tasks, and the
    Java write still checks the exact name against this turn's original USER.
    """
    if question is None or re.search(r"竞品|同行|其他品牌|另一家|举例|假设", question):
        return False
    if not re.search(
        r"(?:品牌(?:名称|名)?|店名|公司名|客户名称)[^\n。\uff01\uff1f?]{0,16}"
        r"(?:叫什么|叫啥|是什么|怎么称呼|是哪个)|"
        r"(?:告诉我|提供)[^\n。\uff01\uff1f?]{0,12}(?:品牌名称|店名|公司名|准确品牌名)",
        question,
    ):
        return False
    answer = content.strip()
    return bool(
        1 < len(answer) <= 100
        and not re.search(
            r"[\n，。；;!?\uff01\uff1f<>`]|怎么|如何|能否|是否|不知道|不清楚|不确定", answer
        )
        and not _NEGATION.search(answer)
        and answer not in {"随便", "你决定", "继续", "好的", "可以", "没有", "稍后", "行吧"}
    )
