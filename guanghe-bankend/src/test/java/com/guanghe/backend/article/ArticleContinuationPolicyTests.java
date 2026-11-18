package com.guanghe.backend.article;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ArticleContinuationPolicyTests {
    @Test void shortAffirmationsNeedSemanticReviewNotKeywordPermission() {
        for(String text:new String[]{"好"," 好的！ ","认可","就按这个写","好,写吧","确认。"})
            assertThat(ArticleContinuationPolicy.confirms(text)).as(text).isTrue();
        for(String text:new String[]{"好但先别写","只认可方案","认可方案","先别写","好，先监测","!!!","。","好?","认 可","好并投稿"})
            assertThat(ArticleContinuationPolicy.confirms(text)).as(text).isFalse();
    }
    @Test void boundedContinuationWordsAreOnlyCandidatesForTheScopedReview() {
        for(String text:new String[]{"继续","继续做","继续写","继续生成","好，继续做","好的,继续写吧！","那就继续吧","请继续写"})
            assertThat(ArticleContinuationPolicy.confirms(text)).as(text).isTrue();
        for(String text:new String[]{"继续讨论","继续分析","继续做监测","继续投稿","继续做，但先别写","先别继续","可以继续吗？","如果可以就继续","“继续做”","继续 写"})
            assertThat(ArticleContinuationPolicy.confirms(text)).as(text).isFalse();
    }
    @Test void explicitDeclinesAndPlanOnlyAcknowledgementsAreNotWritingRequests() {
        for(String text:new String[]{"先别写","只认可方案","好但不要生成","...","暂时不写","确认方案。","等等"})
            assertThat(ArticleContinuationPolicy.declinesOrOnlyAcknowledgesPlan(text)).as(text).isTrue();
        for(String text:new String[]{"帮我写一篇文章","重新生成完整稿","照刚才那版出成稿",
                "不要写报价，按刚才主题生成完整新稿","只同意这里的内容，帮我写完整稿"})
            assertThat(ArticleContinuationPolicy.declinesOrOnlyAcknowledgesPlan(text)).as(text).isFalse();
    }
}
