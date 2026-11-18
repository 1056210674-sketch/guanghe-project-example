package com.guanghe.backend.task;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class CreativeGenerationIntentPolicyTests {

    @ParameterizedTest
    @ValueSource(strings = {
            "帮我生成一张咖啡海报图片",
            "不要文字，生成一张商品主图",
            "画一只戴草帽的猫",
            "请画一只猫",
            "我要一张海报",
            "给我一张商品图",
            "可以帮我生成一张海报吗？",
            "能不能帮我生成一张图？",
            "请生成一张写有“新品上市”的海报",
            "我需要图片生成一个版本",
            "给我图片用于宣传",
            "Generate a product image with a white background"
    })
    void acceptsOnlyDirectImageCreationOrEditRequests(String text) {
        assertThat(CreativeGenerationIntentPolicy.allows("IMAGE", text, false)).as(text).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "生成一个 5 秒的商品视频",
            "请制作一段品牌动画",
            "我要一个视频",
            "Create a five second product video"
    })
    void acceptsOnlyDirectVideoCreationOrEditRequests(String text) {
        assertThat(CreativeGenerationIntentPolicy.allows("VIDEO", text, false)).as(text).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "你可以生成图片吗？",
            "图片应该怎么生成？",
            "请告诉我怎么生成图片",
            "请讲讲制作视频的步骤",
            "给我看看商品图有哪些常见风格",
            "生成四张图需要多少积分？",
            "请先给这段视频制作报价",
            "如果效果不错就生成一张图片",
            "等我确认后再生成视频",
            "不要生成图片，只给我方案",
            "不要给我一张图片",
            "我不要视频",
            "先别做视频",
            "用户说：‘帮我生成一张图片’",
            "请解释“生成一张图片”是什么意思",
            "下面只是示例：生成商品图",
            "I want a quote for creating a video",
            "这张图设计得很好",
            "图片生成效果不错",
            "这个视频制作得不错",
            "画面很好",
            "画质不错",
            "生成图片效果很好",
            "制作视频很专业",
            "生成一张图片效果很好",
            "这张图修改得很好",
            "给我分析一张商品图",
            "我要评价一个视频",
            "生成一张图片的工具坏了",
            "制作一段视频的人是我",
            "画一张图是我的工作",
            "生成一张图片很难",
            "制作一个视频需要很多人",
            "画一只猫是我的工作",
            "画一只猫的人是我",
            "画一只猫很耗时",
            "画一只猫来自官网",
            "画一只猫属于我",
            "Generate a product image is my job",
            "Create a video is expensive",
            "Draw a cat is my work",
            "帮我生成图片的工具坏了",
            "帮我画画的人是我朋友",
            "请画画是我的工作",
            "给我制作视频的人离职了",
            "帮我制作视频的团队很专业",
            "请生成图片的人先离开",
            "我需要生成图片的工具",
            "我要一个视频播放器",
            "给我一个视频链接",
            "给我一个视频地址",
            "给我一个视频编号",
            "给我一个视频报价",
            "给我一张图片链接",
            "给我一张图片地址",
            "给我一张图片说明",
            "给我一张图片分析",
            "给我一张图片报告",
            "给我一张图片编号",
            "给我图片看看",
            "给我视频看看",
            "我需要图片生成工具",
            "给我图片生成工具",
            "我需要视频制作软件",
            "Generate a product image generation tool",
            "Create a video editing tool",
            "这个水印不要去掉",
            "背景不要修改",
            "这张图怎么做成视频"
    })
    void rejectsQuestionsQuotesConditionsAndNegativeRequests(String text) {
        assertThat(CreativeGenerationIntentPolicy.allows("IMAGE", text, true)).as(text).isFalse();
        assertThat(CreativeGenerationIntentPolicy.allows("VIDEO", text, true)).as(text).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"图片", "视频", "一张海报", "一个短片"})
    void bareMediaNounsAreNotDeliveryAuthorization(String text) {
        assertThat(CreativeGenerationIntentPolicy.allows("IMAGE", text, true)).as(text).isFalse();
        assertThat(CreativeGenerationIntentPolicy.allows("VIDEO", text, true)).as(text).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"把背景换成白色", "去掉水印并裁成方图", "请把这张图片的背景改成白色", "修改这张海报",
            "把刚才那张海报背景改红", "把上一张背景改红", "把刚才那张改成红色",
            "参考上一张图生成一张新的海报", "把刚才生成的那张图背景改成白色",
            "把这张图片里面的人物的脸上涂一道红色的痕迹"})
    void attachmentRelativeImageEditRequiresAFrozenCurrentAttachment(String text) {
        assertThat(CreativeGenerationIntentPolicy.allows("IMAGE", text, false)).as(text).isFalse();
        assertThat(CreativeGenerationIntentPolicy.allows("IMAGE", text, true)).as(text).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"再添加一个大狗，这只小狗靠着那只大狗", "添加星星", "再添加一棵树",
            "加上一位人物", "再加上远山", "请再添加一朵云", "麻烦再加上太阳", "添加树"})
    void attachmentAdditionRequiresAFrozenSourceAndAnExplicitObject(String text) {
        assertThat(CreativeGenerationIntentPolicy.allows("IMAGE", text, false)).as(text).isFalse();
        assertThat(CreativeGenerationIntentPolicy.allows("IMAGE", text, true)).as(text).isTrue();
        assertThat(CreativeGenerationIntentPolicy.allows("VIDEO", text, true)).as(text).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"添加", "再添加", "加上", "再加上", "请再添加   ",
            "不要再添加一棵树", "先别再加上一位人物", "再添加一棵树需要多少积分？",
            "再添加一棵树怎么操作", "再加上远山可以吗？", "如果可以再添加一棵树",
            "等我确认后再加上远山", "明天再添加一棵树", "再添加一棵树会更好",
            "再加上远山，你觉得呢？", "用户说：‘再添加一棵树’", "‘再加上远山’"})
    void attachmentAdditionStillRejectsMissingObjectsAndNonExecutionRequests(String text) {
        assertThat(CreativeGenerationIntentPolicy.allows("IMAGE", text, true)).as(text).isFalse();
        assertThat(CreativeGenerationIntentPolicy.allows("VIDEO", text, true)).as(text).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"让它动起来", "把附件做成 5 秒动画", "让上一张图冒热气", "让刚才那张眨眼",
            "让上一张挥手", "让上一张镜头推进", "给上一张图加上动态效果", "让图中主体冒热气",
            "让主体眨眼", "让人物挥手", "让画面有动态效果", "让镜头推进"})
    void attachmentRelativeVideoEditRequiresAFrozenCurrentAttachment(String text) {
        assertThat(CreativeGenerationIntentPolicy.allows("VIDEO", text, false)).as(text).isFalse();
        assertThat(CreativeGenerationIntentPolicy.allows("VIDEO", text, true)).as(text).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"生成一张商品图", "修改这张海报"})
    void imageWordsCannotAuthorizeAVideoTask(String text) {
        assertThat(CreativeGenerationIntentPolicy.allows("VIDEO", text, true)).as(text).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"怎么让图中主体冒热气", "不要让人物挥手", "先别让镜头推进"})
    void attachmentRelativeVideoEditStillRejectsQuestionsAndNegativeCommands(String text) {
        assertThat(CreativeGenerationIntentPolicy.allows("VIDEO", text, false)).as(text).isFalse();
        assertThat(CreativeGenerationIntentPolicy.allows("VIDEO", text, true)).as(text).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"把上一张图背景改红", "把刚才那张海报改成暖色", "把刚才那张改成红色",
            "把上一张换成#ff3344", "让上一张动起来", "让刚才那张图冒热气",
            "把上一张做成视频", "让上一张照片动起来", "让上一张插画动起来",
            "把上张照片改成红色", "让上张照片动起来", "把前一张照片做成视频",
            "上一张照片的背景换成白色", "上一张照片改成红色", "把刚才生成的那张图背景改成白色"})
    void previousImageModeRequiresAnExplicitPreviousImageSemantic(String text) {
        String taskType = text.contains("动起来") || text.contains("冒热气") || text.contains("视频")
                ? "VIDEO" : "IMAGE";
        assertThat(CreativeGenerationIntentPolicy.explicitlyReferencesPreviousImage(taskType, text))
                .as(text).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"把上一张照片做成视频", "把上一张照片做成动态效果", "让上一张照片动起来"})
    void previousImageMotionCannotAuthorizeTheWrongImageTaskType(String text) {
        assertThat(CreativeGenerationIntentPolicy.explicitlyReferencesPreviousImage("IMAGE", text))
                .as(text).isFalse();
        assertThat(CreativeGenerationIntentPolicy.explicitlyReferencesPreviousImage("VIDEO", text))
                .as(text).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"把上一张图表背景改白", "把上一张图纸背景改白", "把上一张图表改成红色",
            "把上一张图纸换成蓝色", "把上一张表格背景改白",
            "把刚才那张发票改红", "让上一张PPT动起来", "上一张文档怎么修改",
            "这个视频怎么让上一张图动起来", "请生成一张新图，上一张图效果不错",
            "上一张图不错，生成一张全新的海报", "让上一张视频动起来",
            "把上一张视频做成动态效果", "让上一张短片眨眼", "让上一张动画挥手",
            "让上一张音频动起来", "让上张视频动起来", "把上张音频做成动态效果",
            "让前一张短片动起来", "让前一张动画挥手", "把前一张音频做成动态效果",
            "让上一张模型动起来，然后生成一个新视频", "让上张方案动起来，然后生成一个新视频",
            "把前一张产品做成视频，然后生成一个新视频",
            "把上一张模型改成红色，然后生成一张新海报",
            "把上张方案背景改白，然后生成一张新海报",
            "把前一张产品颜色调暖，然后生成一张新海报"})
    void previousImageModeRejectsNonImageObjectsAndConsultation(String text) {
        assertThat(CreativeGenerationIntentPolicy.explicitlyReferencesPreviousImage("IMAGE", text)).as(text).isFalse();
        assertThat(CreativeGenerationIntentPolicy.explicitlyReferencesPreviousImage("VIDEO", text)).as(text).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "你按这张图作参考生成一版带红痕的",
            "参考刚才上传的图片生成一张新海报",
            "根据上一轮我上传的图片创作一张新图",
            "把前面我发的照片改成暖色",
            "让刚才上传的图动起来",
            "让刚才提供的图片动起来",
            "按刚才上传的图继续加深红痕",
            "按我刚才上传的图片重新生成一版"
    })
    void previousUserAttachmentRequiresAnExplicitPriorUploadSemantic(String text) {
        String taskType = text.contains("动起来") ? "VIDEO" : "IMAGE";
        assertThat(CreativeGenerationIntentPolicy.explicitlyReferencesPreviousUserAttachment(taskType, text))
                .as(text).isTrue();
        assertThat(CreativeGenerationIntentPolicy.allows(taskType, text, true)).as(text).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "分析这张图上的红痕",
            "这张图为什么有红痕",
            "不要按这张图生成",
            "如果可以就参考刚才上传的图片生成",
            "参考上一张生成图改成暖色",
            "把前面发的文档改一下",
            "按这张图作参考生成一版带红痕的会更好",
            "按这张图作参考生成一版带红痕的是个好主意",
            "按这张图作参考生成一版带红痕的方案不错",
            "按这张图作参考生成一版带红痕的会更好一点",
            "按这张图作参考生成一版带红痕的会更好一些",
            "按这张图作参考生成一版带红痕的可能比较好",
            "按这张图作参考生成一版带红痕的，我觉得不错呀",
            "按这张图作参考生成一版带红痕的，我觉得可行的",
            "按这张图作参考生成一版带红痕的，值得试试",
            "按这张图作参考生成一版带红痕的，好吗？",
            "按这张图作参考生成一版带红痕的，你觉得呢？",
            "按这张图作参考生成一版带红痕的，应该可以吧",
            "按这张图作参考生成一版带红痕的，你觉得这样行吗？",
            "按这张图作参考生成一版带红痕的，这样行不行？",
            "按这张图作参考生成一版带红痕的，要不要这样做？",
            "按这张图作参考生成一版带红痕的，我建议这样做。",
            "按这张图作参考生成一版带红痕的，不妨试试。",
            "按我刚才上传的图片重新生成一版会更好一点...",
            "按我刚才上传的图片重新生成一版，可以吗？",
            "让我刚才提供的图片动起来，可以吗？"
    })
    void previousUserAttachmentRejectsAnalysisNegationConditionsAndNonImages(String text) {
        assertThat(CreativeGenerationIntentPolicy.explicitlyReferencesPreviousUserAttachment("IMAGE", text))
                .as(text).isFalse();
        assertThat(CreativeGenerationIntentPolicy.explicitlyReferencesPreviousUserAttachment("VIDEO", text))
                .as(text).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "按这张图作参考生成一版带红痕的会更好",
            "按这张图作参考生成一版带红痕的是个好主意",
            "按这张图作参考生成一版带红痕的方案不错",
            "按这张图作参考生成一版带红痕的会更好一点",
            "按这张图作参考生成一版带红痕的会更好一些",
            "按这张图作参考生成一版带红痕的可能比较好",
            "按这张图作参考生成一版带红痕的，我觉得不错呀",
            "按这张图作参考生成一版带红痕的，我觉得可行的",
            "按这张图作参考生成一版带红痕的，值得试试",
            "按这张图作参考生成一版带红痕的，好吗？",
            "按这张图作参考生成一版带红痕的，你觉得呢？",
            "按这张图作参考生成一版带红痕的，应该可以吧",
            "按这张图作参考生成一版带红痕的，你觉得这样行吗？",
            "按这张图作参考生成一版带红痕的，这样行不行？",
            "按这张图作参考生成一版带红痕的，要不要这样做？",
            "按这张图作参考生成一版带红痕的，我建议这样做。",
            "按这张图作参考生成一版带红痕的，不妨试试。",
            "按我刚才上传的图片重新生成一版会更好一点...",
            "按我刚才上传的图片重新生成一版，可以吗？",
            "让我刚才提供的图片动起来，可以吗？"
    })
    void previousUserAttachmentEvaluationsNeverAuthorizePaidGeneration(String text) {
        assertThat(CreativeGenerationIntentPolicy.explicitlyReferencesPreviousUserAttachment("IMAGE", text))
                .as(text).isFalse();
        assertThat(CreativeGenerationIntentPolicy.allows("IMAGE", text, true)).as(text).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "你看这张图，按这张图作参考生成一版带红痕的",
            "我觉得红痕太浅了，按这张图作参考生成一版带红痕的",
            "我认为背景不够亮，请按这张图作参考生成一版更亮的"
    })
    void laterExplicitImageCommandOverridesEarlierObservation(String text) {
        assertThat(CreativeGenerationIntentPolicy.explicitlyReferencesPreviousUserAttachment("IMAGE", text))
                .as(text).isTrue();
        assertThat(CreativeGenerationIntentPolicy.allows("IMAGE", text, true)).as(text).isTrue();
    }

}
