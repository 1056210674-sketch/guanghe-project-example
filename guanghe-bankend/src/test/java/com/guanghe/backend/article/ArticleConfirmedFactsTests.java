package com.guanghe.backend.article;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ArticleConfirmedFactsTests {
    private final ObjectMapper json=new ObjectMapper();
    private final String facts="合成星河AI 成立于 2023 年、落地广州琶洲；主营企业智能体；服务中小企业。";
    private String source() { return "**搜到了什么**\n- [来源：https://example.com/article ]。摘录里提到："+facts
            +"\n- 不是独立测评。\n**我问两件事：**\n上面哪些是准确、可以对外写的？\n你确认后我写一篇。"; }
    private ArticleFullMapper.ConfirmedFactSource pair(String source,String confirmation) {
        return new ArticleFullMapper.ConfirmedFactSource(101,102,source,confirmation,
                OffsetDateTime.parse("2026-10-10T04:05:00Z"),OffsetDateTime.parse("2026-10-10T04:06:00Z"));
    }
    private com.fasterxml.jackson.databind.node.ArrayNode project(String source,String confirmation) {
        return ArticleConfirmedFacts.project(json,10,20,json.createObjectNode().put("name","合成星河AI"),
                List.of(pair(source,confirmation)));
    }
    @Test void projectsOnlyCompleteExplicitlyConfirmedFactsNotAdviceOrOffer() {
        var result=project(source(),"是的，你获取的消息都是准确的");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).path("sourceExcerpt").asText()).isEqualTo(facts);
        assertThat(result.get(0).path("facts")).hasSize(3);
        assertThat(result.get(0).path("sourceUrl").asText()).isEqualTo("https://example.com/article");
        assertThat(result.toString()).doesNotContain("确认后我写","不是独立测评");
    }
    @Test void acceptsLabeledFactListButNotGeneralAcknowledgementOrPartialConfirmation() {
        String list="待确认品牌资料：\n- 公司全称：合成星河AI\n- 成立时间：2023年\n- 服务对象：中小企业\n\n这些是否准确，可以对外写吗？";
        assertThat(project(list,"这些资料都准确")).hasSize(1);
        for(String confirmation:List.of("好","认可这个方案","这些资料都准确，但地址不对","部分消息准确", "如果资料都准确就写吧"))
            assertThat(project(source(),confirmation)).isEmpty();
        assertThat(project(source().replace("合成星河AI","另一个客户"),"消息都是准确的")).isEmpty();
        assertThat(project(source().replace("哪些是准确、可以对外写的","要不要按这个方案写"),"消息都是准确的")).isEmpty();
        assertThat(project(source()+"补充".repeat(8000),"消息都是准确的")).isEmpty();
        assertThat(project(source().replace("服务中小企业。","服务中小企业"),"消息都是准确的")).isEmpty();
    }
    @Test void candidateQueryFencesScopeAdjacencyAndForgetBoundaryWithoutRequiringConfirmationRunSuccess() {
        var config=new Configuration();config.addMapper(ArticleFullMapper.class);
        String sql=config.getMappedStatement(ArticleFullMapper.class.getName()+".confirmedFactSources")
                .getBoundSql(Map.of()).getSql().replaceAll("\\s+"," ");
        assertThat(sql).contains("u.user_id=? AND u.conversation_id=?", "ur.user_id=? AND ur.conversation_id=?",
                "ar.user_id=? AND ar.conversation_id=?", "ur.selected_space_id=?", "ar.selected_space_id=?",
                "m.sequence_no<u.sequence_no", "ORDER BY m.sequence_no DESC LIMIT 1",
                "u.role='USER' AND u.status='COMPLETED'", "ar.status='COMPLETED'",
                "u.reply_to_message_id IS NULL OR u.reply_to_message_id=a.id", "ar.completed_at<=u.created_at",
                "memory.project_id IS NULL OR memory.project_id=?", "memory.history_cutoff_at>a.created_at",
                "BETWEEN 1 AND 16000", "AND u.content->>'text' ~ '(信息|资料|消息|事实|内容|这些|以上|上面)")
                .doesNotContain("ur.status=", "ur.error_code", "left(", "INTERVAL '24 hours'");
    }
}
