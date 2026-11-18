package com.guanghe.backend.task;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Fail-closed USER-language gate for native paid media creation.
 *
 * <p>The model chooses a tool, but that choice is not billing authorization. This policy only
 * accepts a direct request in the current USER message. Capability questions, price questions,
 * deferred/conditional requests, refusals and quoted examples are deliberately rejected.</p>
 */
final class CreativeGenerationIntentPolicy {
    private static final Set<String> TASK_TYPES = Set.of("IMAGE", "VIDEO");
    private static final String IMAGE_TARGET =
            "(?:图(?:片|像|稿|标|片素材)?|照片|相片|海报|封面|配图|插画|主图|详情图|头像|壁纸|视觉|logo|贴纸|画面)";
    private static final String VIDEO_TARGET =
            "(?:视频|短视频|短片|影片|片子|动画|动效|动态图|mv|reel)";
    private static final String CREATE =
            "(?:生成|创作|制作|做|绘制|设计|产出|出)";
    private static final String EDIT =
            "(?:改|修改|编辑|调整|重绘|重做|替换|换成|去掉|移除|添加|加上|涂上|涂抹|涂|画上|抹上|变成|转成|转换|做成|扩图|抠图|修图|美化|续作|延展|裁剪|裁成)";
    private static final String FROZEN_IMAGE_REFERENCE =
            "(?:(?:这张|那张|上图|原图|附件|它|这个)"
                    + "(?:图|图片|照片|相片|海报|封面|配图|插画|主图|详情图|画面)?|图中主体|主体|人物|画面|镜头)";
    private static final String PREVIOUS_REFERENCE = "(?:上一张|上张|前一张|刚才(?:的|那张)?)";
    private static final String SAME_CLAUSE_CHAR = "[^，,。；;！!\\n\\r]";
    private static final String PREVIOUS_IMAGE_EDIT_COMMAND =
            "(?:改|修改|编辑|调整|重绘|重做|替换|换成|去掉|移除|添加|加上|变成|转成|转换|做成|扩图|抠图|修图|美化|续作|延展|裁剪|裁成)";
    private static final String COMMAND_PREFIX =
            "(?:(?:请问能不能帮我|能不能帮我|可以帮我|请|麻烦|劳驾|帮我|给我|替我|为我|直接|现在|立即|马上)\\s*){1,3}";
    private static final String PERSONAL_INTENT_PREFIX =
            "(?:(?:我要|我需要|我想要|我想)\\s*)";
    private static final String QUANTIFIED_DELIVERY =
            "(?:[1-9一二三四几两]\\s*(?:个|张|幅|只|段|部|条|款|版|套|些)?)";
    private static final String DELIVERY_DETAIL =
            "(?:(?!(?:评价|点评|分析|识别|描述|解释|介绍|比较|搜索|查找|找|推荐|教程|方法|报价)).){0,16}";

    private static final Pattern IMAGE_DIRECT = Pattern.compile(
            "(?is)^(?:(?:" + COMMAND_PREFIX + "|" + PERSONAL_INTENT_PREFIX + ")"
                    + CREATE + ".{0,32}" + IMAGE_TARGET
                    + "|" + CREATE + "\\s*" + QUANTIFIED_DELIVERY + ".{0,32}" + IMAGE_TARGET
                    + "|(?:(?:请|麻烦|劳驾)\\s*)?(?:我要|我需要|给我|来)\\s*"
                    + "(?:" + QUANTIFIED_DELIVERY + "\\s*)?" + DELIVERY_DETAIL + IMAGE_TARGET
                    + "|" + COMMAND_PREFIX + "画.{0,80}"
                    + "|画\\s*" + QUANTIFIED_DELIVERY + ".{0,80})");
    private static final Pattern VIDEO_DIRECT = Pattern.compile(
            "(?is)^(?:(?:" + COMMAND_PREFIX + "|" + PERSONAL_INTENT_PREFIX + ")"
                    + CREATE + ".{0,32}" + VIDEO_TARGET
                    + "|" + CREATE + "\\s*" + QUANTIFIED_DELIVERY + ".{0,32}" + VIDEO_TARGET
                    + "|(?:(?:请|麻烦|劳驾)\\s*)?(?:我要|我需要|给我|来)\\s*"
                    + "(?:" + QUANTIFIED_DELIVERY + "\\s*)?" + DELIVERY_DETAIL + VIDEO_TARGET + ")");
    private static final Pattern ATTACHMENT_IMAGE_EDIT = Pattern.compile(
            "(?is)^(?:(?:" + COMMAND_PREFIX + "|" + PERSONAL_INTENT_PREFIX + ")" + EDIT + ".{0,32}" + IMAGE_TARGET
                    + "|" + EDIT + "\\s*" + FROZEN_IMAGE_REFERENCE + ".{0,24}"
                    + "|(?:" + COMMAND_PREFIX + ")?(?:把|将)(?:" + FROZEN_IMAGE_REFERENCE + ")?.{0,16}" + EDIT
                    + ".{0,24}(?:背景|颜色|色调|文字|水印|尺寸|比例|主体|人物|脸|面部|痕迹|伤痕|疤痕|商品|画面|风格|清晰度|构图)"
                    + "|(?:" + COMMAND_PREFIX + ")?(?:把|将)(?:" + FROZEN_IMAGE_REFERENCE + ")?.{0,8}"
                    + "(?:背景|颜色|色调|文字|水印|尺寸|比例|主体|人物|脸|面部|痕迹|伤痕|疤痕|商品|画面|风格|清晰度|构图).{0,8}" + EDIT
                    + "|(?:" + COMMAND_PREFIX + ")?(?:去掉|移除|替换|调整|修改|裁剪|裁成|扩图|抠图|修图|美化).{0,24}"
                    + "|(?:" + COMMAND_PREFIX + ")?(?:再\\s*)?(?:添加|加上)\\s*\\S.{0,23})");
    private static final Pattern ATTACHMENT_VIDEO_EDIT = Pattern.compile(
            "(?is)^(?:(?:" + COMMAND_PREFIX + ")?(?:把|将)" + FROZEN_IMAGE_REFERENCE
                    + ".{0,24}(?:做成|转成|变成|制作成|添加|加上).{0,20}(?:视频|短片|动画|动效|动态图|动态效果)"
                    + "|(?:让|使)" + FROZEN_IMAGE_REFERENCE + ".{0,16}"
                    + "(?:动起来|动一动|冒热气|眨眼|挥手|推进|镜头推进|产生动态效果|有动态效果)"
                    + "|(?:(?:给|为)\\s*)?" + FROZEN_IMAGE_REFERENCE + ".{0,12}(?:添加|加上)?.{0,8}"
                    + "(?:冒热气|眨眼|挥手|镜头推进|动态效果))");
    private static final String PREVIOUS_IMAGE_NOUN =
            "(?:图(?!表|纸)(?:片(?:素材)?|像|稿|标)?|照片|相片|海报|封面|配图|插画|主图|详情图|头像|壁纸|视觉|logo|贴纸|画面)";
    private static final String PREVIOUS_IMAGE_OBJECT =
            "(?:" + PREVIOUS_REFERENCE + "\\s*(?:的\\s*)?(?:(?:生成|创作|制作)(?:的)?\\s*)?"
                    + "(?:那张\\s*)?" + PREVIOUS_IMAGE_NOUN + ")";
    private static final String PREVIOUS_OMITTED_IMAGE =
            "(?:" + PREVIOUS_REFERENCE + "\\s*(?:的\\s*)?)";
    private static final String PREVIOUS_IMAGE_ATTRIBUTE =
            "(?:背景|颜色|色调|文字|水印|尺寸|比例|主体|人物|商品|画面|风格|清晰度|构图)";
    private static final String PREVIOUS_IMAGE_MOTION =
            "(?:动起来|动一动|冒热气|眨眼|挥手|镜头推进|产生动态效果|有动态效果|动态效果)";
    private static final String PREVIOUS_IMAGE_TO_VIDEO =
            "(?:(?:做成|转成|变成|制作成)\\s*(?:视频|短片|动画|动效|动态图|动态效果))";
    private static final Pattern PREVIOUS_NAMED_IMAGE_CONTINUATION = Pattern.compile(
            "(?is)(?:(?:(?:把|将)\\s*)?" + PREVIOUS_IMAGE_OBJECT + SAME_CLAUSE_CHAR + "{0,24}"
                    + PREVIOUS_IMAGE_EDIT_COMMAND
                    + "|(?:修改|编辑|调整|重绘|重做|替换|去掉|移除|扩图|抠图|修图|美化|裁剪|裁成)"
                    + "\\s*" + PREVIOUS_IMAGE_OBJECT
                    + "|(?:参考|基于|使用|沿用)\\s*" + PREVIOUS_IMAGE_OBJECT + SAME_CLAUSE_CHAR
                    + "{0,16}(?:生成|创作|制作|绘制|设计|重做|续作|延展))");
    private static final Pattern PREVIOUS_IMAGE_EDIT_TARGET = Pattern.compile(
            "(?is)" + PREVIOUS_OMITTED_IMAGE + "(?:" + PREVIOUS_IMAGE_ATTRIBUTE
                    + SAME_CLAUSE_CHAR + "{0,8}" + PREVIOUS_IMAGE_EDIT_COMMAND + "|"
                    + PREVIOUS_IMAGE_EDIT_COMMAND + SAME_CLAUSE_CHAR + "{0,8}"
                    + PREVIOUS_IMAGE_ATTRIBUTE + ")");
    private static final Pattern PREVIOUS_IMAGE_COLOR_CHANGE = Pattern.compile(
            "(?is)" + PREVIOUS_OMITTED_IMAGE + "(?:改成|换成|变成|调整为|设为)"
                    + SAME_CLAUSE_CHAR + "{0,8}"
                    + "(?:(?:深|浅|暖|冷)?(?:红|橙|黄|绿|青|蓝|紫|粉|黑|白|灰|棕|褐|金|银)(?:色|调)?"
                    + "|透明|#[0-9a-f]{3,8}|rgb(?:a)?\\([^)]{3,40}\\))");
    private static final Pattern PREVIOUS_IMAGE_MOTION_TARGET = Pattern.compile(
            "(?is)(?:(?:让|使)\\s*" + PREVIOUS_IMAGE_OBJECT + SAME_CLAUSE_CHAR + "{0,16}"
                    + PREVIOUS_IMAGE_MOTION
                    + "|(?:把|将)\\s*" + PREVIOUS_IMAGE_OBJECT + SAME_CLAUSE_CHAR + "{0,12}"
                    + PREVIOUS_IMAGE_TO_VIDEO
                    + "|(?:(?:给|为)\\s*)?" + PREVIOUS_IMAGE_OBJECT + SAME_CLAUSE_CHAR + "{0,12}"
                    + "(?:添加|加上)?" + SAME_CLAUSE_CHAR + "{0,8}" + PREVIOUS_IMAGE_MOTION
                    + "|(?:让|使)\\s*" + PREVIOUS_OMITTED_IMAGE + PREVIOUS_IMAGE_MOTION
                    + "|(?:把|将)\\s*" + PREVIOUS_OMITTED_IMAGE + PREVIOUS_IMAGE_TO_VIDEO
                    + "|(?:(?:给|为)\\s*)?" + PREVIOUS_OMITTED_IMAGE + "(?:添加|加上)?"
                    + PREVIOUS_IMAGE_MOTION + ")");
    private static final String PREVIOUS_USER_ATTACHMENT_OBJECT =
            "(?:(?:这张|那张)\\s*" + PREVIOUS_IMAGE_NOUN
                    + "|(?:我(?:刚才|前面|之前|上(?:一)?轮)|(?:刚才|前面|之前|上(?:一)?轮)(?:我)?)"
                    + "(?:上传|发|发送|提供|给)(?:的)?\\s*(?:那张|这张)?\\s*"
                    + PREVIOUS_IMAGE_NOUN + ")";
    private static final Pattern PREVIOUS_USER_ATTACHMENT_IMAGE = Pattern.compile(
            "(?is)(?:(?:按|根据|参考|基于|使用|用|沿用)\\s*" + PREVIOUS_USER_ATTACHMENT_OBJECT
                    + SAME_CLAUSE_CHAR + "{0,24}(?:生成|创作|制作|绘制|设计|重做|续作|延展|"
                    + "继续(?:修改|编辑|调整)?|加深|改深|改|修改|编辑|调整|重绘)"
                    + "|(?:把|将)\\s*" + PREVIOUS_USER_ATTACHMENT_OBJECT + SAME_CLAUSE_CHAR
                    + "{0,24}" + EDIT + ")");
    private static final Pattern PREVIOUS_USER_ATTACHMENT_VIDEO = Pattern.compile(
            "(?is)(?:(?:把|将)\\s*" + PREVIOUS_USER_ATTACHMENT_OBJECT + SAME_CLAUSE_CHAR
                    + "{0,24}" + PREVIOUS_IMAGE_TO_VIDEO
                    + "|(?:让|使)\\s*" + PREVIOUS_USER_ATTACHMENT_OBJECT + SAME_CLAUSE_CHAR
                    + "{0,16}" + PREVIOUS_IMAGE_MOTION + ")");
    private static final Pattern ENGLISH_IMAGE_DIRECT = Pattern.compile(
            "(?is)^(?:(?:please|could you|can you|i (?:want|need)(?: you to)?)\\s+)?"
                    + "(?:generate|create|make|draw|design)\\b.{0,40}"
                    + "\\b(?:image|picture|photo|poster|cover|illustration|logo)\\b");
    private static final Pattern ENGLISH_IMAGE_EDIT = Pattern.compile(
            "(?is)^(?:(?:please|could you|can you|i (?:want|need)(?: you to)?)\\s+)?"
                    + "(?:edit|modify|retouch|transform)\\b.{0,40}"
                    + "\\b(?:image|picture|photo|poster|cover|illustration|logo)\\b");
    private static final Pattern ENGLISH_VIDEO_DIRECT = Pattern.compile(
            "(?is)^(?:(?:please|could you|can you|i (?:want|need)(?: you to)?)\\s+)?"
                    + "(?:generate|create|make|animate)\\b.{0,40}"
                    + "\\b(?:video|clip|animation|reel)\\b");
    private static final Pattern ENGLISH_VIDEO_EDIT = Pattern.compile(
            "(?is)^(?:(?:please|could you|can you|i (?:want|need)(?: you to)?)\\s+)?"
                    + "(?:edit|modify|transform)\\b.{0,40}"
                    + "\\b(?:video|clip|animation|reel)\\b");

    private static final Pattern CONDITIONAL = Pattern.compile(
            "(?is)(?:如果|假如|要是|倘若|若是|只要.{0,40}(?:就|再)|等(?:我|到).{0,40}(?:再|才)|"
                    + "\\b(?:if|unless|provided that|once)\\b)");
    private static final Pattern PRICE_OR_ESTIMATE = Pattern.compile(
            "(?is)(?:多少(?:积分|点数|点|钱|费用)|多少钱|怎么收费|如何收费|报价(?:是|为|要|多少)?|"
                    + "(?:价格|费用|积分|点数|成本|耗时|时长|多久).{0,12}(?:多少|几|怎么算|如何|怎么|吗|呢|\\?)|"
                    + "\\b(?:how much|price|pricing|quote|cost|how long)\\b)");
    private static final Pattern META_OR_QUOTE = Pattern.compile(
            "(?is)(?:引用|复述|翻译|解释|举例|例子|例如|示例|假设|原话|这句话|这段话|"
                    + "(?:用户|客户|他|她|对方).{0,8}(?:说|写|问)|"
                    + "\\b(?:quote|translate|explain|example|hypothetical)\\b)");
    private static final Pattern INFORMATIONAL = Pattern.compile(
            "(?is)(?:如何|怎么|怎样|为什么|能否|是否|可不可以|会不会|支持吗|是什么|什么意思|"
                    + "有什么区别|有哪些|需要什么|有什么要求|想了解|介绍一下|说明|讲讲|告诉我|给我看|"
                    + "教程|教学|步骤|方法|做法|流程|"
                    + "\\b(?:how (?:do|can|to)|what is|why|whether|can (?:it|you)|do you support)\\b)");
    private static final Pattern DECLARATIVE_ASSESSMENT = Pattern.compile(
            "(?is)(?:(?:生成|制作|设计|修改|编辑|调整|重绘|绘制).{0,36}(?:效果|质量|水平|结果).{0,8}"
                    + "(?:很好|不错|专业|很棒|优秀|一般|很差)|(?:这张|那张|这个|图片|图像|视频).{0,20}"
                    + "(?:做得|设计得|修改得|编辑得|制作得).{0,8}(?:很好|不错|专业|很棒|优秀|一般|很差))");
    private static final Pattern PROPOSAL_EVALUATION = Pattern.compile(
            "(?is)(?:(?:(?:我觉得|我感觉|感觉|这样|这么做)?(?:会|应该|可能|或许)?"
                    + "(?:比较|更|挺|很|还)?(?:好|不错|合适|可行|可以)(?:一点|一些|些|的)?"
                    + "(?:呀|啊|呢|吧)?|(?:值得(?:尝试|试试|考虑)|可以考虑)(?:一下|的)?"
                    + "(?:呀|啊|呢|吧)?|(?:是|算是|是个)(?:好主意|好方案|建议)|"
                    + "方案(?:很)?(?:好|不错|可行)|(?:好吗|好不好|怎么样|你觉得呢|你看呢))"
                    + "(?:[?？。.!！…])*$)");
    private static final Pattern DISCUSSION_OR_SUGGESTION = Pattern.compile(
            "(?is)(?:我(?:觉得|认为|建议|推荐|倾向)|你(?:觉得|认为|看)|不妨|何不|要不(?:要)?|是否应该|"
                    + "(?:这样|这么做|这个方案|这个思路).{0,16}(?:行不行|可不可以|合不合适|"
                    + "行吗|可以吗|合适吗)|(?:行不行|可不可以|合不合适|要不要).{0,16}(?:这样|这么做|试试)|"
                    + "(?:行吗|可以吗|合适吗))");
    private static final Pattern DECLARATIVE_MEDIA_NOMINAL_TAIL = Pattern.compile(
            "(?is)(?:" + IMAGE_TARGET + "|" + VIDEO_TARGET + ")\\s*(?:"
                    + "的(?:工具|人|功能|模型|软件|系统|教程|方法|流程|能力|团队|公司|作者|服务|平台)"
                    + "|是(?:我|他|她|这|那|其).{0,12}(?:工作|职业|任务|职责|作品|东西))");
    private static final Pattern DECLARATIVE_MEDIA_TAIL = Pattern.compile(
            "(?is)(?:" + IMAGE_TARGET + "|" + VIDEO_TARGET + ")\\s*(?:"
                    + "(?:很|真|太|比较)?(?:难|容易|复杂|简单|贵|便宜)|(?:是|有|需要|可以|能够|会|由|被))");
    private static final Pattern DECLARATIVE_BARE_DRAW_TAIL = Pattern.compile(
            "(?is)^(?:" + COMMAND_PREFIX + ")?画.{0,64}(?:"
                    + "的(?:人|工作|职业|任务|工具|作者|来源|团队)"
                    + "|是(?:我|他|她|这|那|其).{0,12}(?:工作|职业|任务|职责|朋友|作品|东西)"
                    + "|(?:很|真|太|比较)?耗时|来自|属于)");
    private static final Pattern DECLARATIVE_ENGLISH_MEDIA_TAIL = Pattern.compile(
            "(?is)\\b(?:image|picture|photo|poster|cover|illustration|logo|video|clip|animation|reel)\\b"
                    + "\\s+(?:is|are|was|were|belongs?\\s+to|comes?\\s+from|came\\s+from|"
                    + "costs?|requires?|needs?)\\b");
    private static final Pattern NON_DELIVERY_MEDIA_TAIL = Pattern.compile(
            "(?is)(?:" + IMAGE_TARGET + "|" + VIDEO_TARGET + ")\\s*(?:"
                    + "(?:(?:生成|制作|编辑|处理|绘图|设计)\\s*)?"
                    + "(?:播放器|链接|地址|网址|编号|报价|说明|分析|报告|教程|工具|软件|系统|平台|模型|生成器|看看|预览))"
                    + "|\\b(?:image|picture|photo|video|clip|animation|reel)\\b\\s+"
                    + "(?:(?:generation|creation|editing|processing|design)\\s+)?"
                    + "(?:player|link|url|id|quote|report|analysis|generator|tool|software|system|platform|model|preview)\\b");
    private static final Pattern EXPLICIT_INTENT_PREFIX = Pattern.compile(
            "(?is)^(?:" + COMMAND_PREFIX + "|" + PERSONAL_INTENT_PREFIX + "|(?:让|使)).*");
    private static final Pattern DIRECT_POLITE_REQUEST = Pattern.compile(
            "(?is)^(?:请|麻烦|劳驾|帮我|给我|替我|能不能帮我|可以帮我|请帮我|"
                    + "please\\b|could you\\b|can you\\b).*");
    private static final Pattern NEGATIVE_COMMAND = Pattern.compile(
            "(?is)(?:不要|别|先别|暂时别|先不要|暂时不要|无需|不需要|不用|不想|不打算|禁止|停止|取消|"
                    + "do not|don't|dont|no need to|stop|cancel).{0,12}(?:" + CREATE + "|" + EDIT
                    + "|给我|来.{0,4}(?:" + IMAGE_TARGET + "|" + VIDEO_TARGET + ")|" + IMAGE_TARGET + "|" + VIDEO_TARGET
                    + "|generate|create|make|draw|design|edit|modify|animate)");
    private static final String CLAUSE_SEPARATOR = "[，,。；;！!？?\\n\\r]+";

    private CreativeGenerationIntentPolicy() { }

    static boolean allows(String taskType, String rawText, boolean hasFrozenSource) {
        if (!TASK_TYPES.contains(taskType) || rawText == null || rawText.isBlank()) return false;
        String text = normalize(removeQuotedSegments(rawText));
        if (text.isBlank() || CONDITIONAL.matcher(text).find()
                || PRICE_OR_ESTIMATE.matcher(text).find() || META_OR_QUOTE.matcher(text).find()
                || DECLARATIVE_ASSESSMENT.matcher(text).find()
                || DECLARATIVE_ENGLISH_MEDIA_TAIL.matcher(text).find()) {
            return false;
        }
        if (endsInProposalOrDiscussion(text)) return false;
        for (String rawClause : text.split(CLAUSE_SEPARATOR)) {
            String clause = rawClause.strip();
            if (clause.isEmpty() || NEGATIVE_COMMAND.matcher(clause).find()) continue;
            if (PROPOSAL_EVALUATION.matcher(clause).find()
                    || DISCUSSION_OR_SUGGESTION.matcher(clause).find()) continue;
            if (DECLARATIVE_BARE_DRAW_TAIL.matcher(clause).find()) continue;
            if (DECLARATIVE_MEDIA_NOMINAL_TAIL.matcher(clause).find()
                    || NON_DELIVERY_MEDIA_TAIL.matcher(clause).find()) continue;
            if (DECLARATIVE_MEDIA_TAIL.matcher(clause).find()
                    && !EXPLICIT_INTENT_PREFIX.matcher(clause).matches()) continue;
            boolean direct = direct(taskType, clause, hasFrozenSource);
            if (!direct) continue;
            if (INFORMATIONAL.matcher(clause).find()) continue;
            boolean question = clause.contains("?") || clause.endsWith("吗") || clause.endsWith("么")
                    || clause.endsWith("呢");
            if (!question || DIRECT_POLITE_REQUEST.matcher(clause).matches()) return true;
        }
        return false;
    }

    static boolean explicitlyReferencesPreviousImage(String taskType, String rawText) {
        if (!TASK_TYPES.contains(taskType) || rawText == null || rawText.isBlank()) return false;
        String text = normalize(removeQuotedSegments(rawText));
        if (INFORMATIONAL.matcher(text).find()
                || NEGATIVE_COMMAND.matcher(text).find() || CONDITIONAL.matcher(text).find()
                || PRICE_OR_ESTIMATE.matcher(text).find() || META_OR_QUOTE.matcher(text).find()
                || endsInProposalOrDiscussion(text)) return false;
        if ("IMAGE".equals(taskType) && PREVIOUS_IMAGE_MOTION_TARGET.matcher(text).find()) return false;
        return "IMAGE".equals(taskType)
                ? PREVIOUS_NAMED_IMAGE_CONTINUATION.matcher(text).find()
                        || PREVIOUS_IMAGE_EDIT_TARGET.matcher(text).find()
                        || PREVIOUS_IMAGE_COLOR_CHANGE.matcher(text).find()
                : PREVIOUS_IMAGE_MOTION_TARGET.matcher(text).find();
    }

    static boolean explicitlyReferencesPreviousUserAttachment(String taskType, String rawText) {
        if (!TASK_TYPES.contains(taskType) || rawText == null || rawText.isBlank()) return false;
        String text = normalize(removeQuotedSegments(rawText));
        if (INFORMATIONAL.matcher(text).find()
                || NEGATIVE_COMMAND.matcher(text).find() || CONDITIONAL.matcher(text).find()
                || PRICE_OR_ESTIMATE.matcher(text).find() || META_OR_QUOTE.matcher(text).find()
                || endsInProposalOrDiscussion(text)) return false;
        return "IMAGE".equals(taskType)
                ? PREVIOUS_USER_ATTACHMENT_IMAGE.matcher(text).find()
                : PREVIOUS_USER_ATTACHMENT_VIDEO.matcher(text).find();
    }

    private static boolean direct(String taskType, String clause, boolean hasFrozenSource) {
        if ("IMAGE".equals(taskType)) {
            return IMAGE_DIRECT.matcher(clause).find() || ENGLISH_IMAGE_DIRECT.matcher(clause).find()
                    || hasFrozenSource && (ATTACHMENT_IMAGE_EDIT.matcher(clause).find()
                    || ENGLISH_IMAGE_EDIT.matcher(clause).find()
                    || explicitlyReferencesPreviousImage("IMAGE", clause)
                    || explicitlyReferencesPreviousUserAttachment("IMAGE", clause));
        }
        if (hasFrozenSource && explicitlyReferencesPreviousImage("VIDEO", clause)) return true;
        if (hasFrozenSource && explicitlyReferencesPreviousUserAttachment("VIDEO", clause)) return true;
        if (ATTACHMENT_VIDEO_EDIT.matcher(clause).find()
                || ENGLISH_VIDEO_EDIT.matcher(clause).find()) return hasFrozenSource;
        return VIDEO_DIRECT.matcher(clause).find() || ENGLISH_VIDEO_DIRECT.matcher(clause).find();
    }

    private static boolean endsInProposalOrDiscussion(String text) {
        String[] clauses = text.split(CLAUSE_SEPARATOR);
        for (int index = clauses.length - 1; index >= 0; index--) {
            String clause = clauses[index].strip();
            if (clause.isEmpty()) continue;
            return PROPOSAL_EVALUATION.matcher(clause).find()
                    || DISCUSSION_OR_SUGGESTION.matcher(clause).find();
        }
        return false;
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("[\\t\\x0B\\f ]+", " ").strip();
    }

    private static String removeQuotedSegments(String value) {
        StringBuilder out = new StringBuilder(value.length());
        char closing = 0;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (closing != 0) {
                if (current == closing) closing = 0;
                continue;
            }
            closing = switch (current) {
                case '“' -> '”';
                case '‘' -> '’';
                case '「' -> '」';
                case '『' -> '』';
                case '"' -> '"';
                case '\'' -> '\'';
                case '`' -> '`';
                default -> 0;
            };
            if (closing == 0) out.append(current);
        }
        return out.toString();
    }
}
