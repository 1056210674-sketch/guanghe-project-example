package com.guanghe.backend.article;

import java.util.Map;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ArticleContinuationMapperTests {
    @Test void candidateIsAdjacentOwnedCompletedUnsubmittedAndSupportsProjectIdentityWithoutSpace() {
        var sql=statement("writingOffer");
        assertThat(sql).contains("user_id=? AND conversation_id=? AND sequence_no < ?",
                "ORDER BY sequence_no DESC LIMIT 1", "p.role='ASSISTANT' AND p.status='COMPLETED'",
                "u.role='USER' AND u.status='COMPLETED'", "u.agent_run_id=r.id",
                "r.status='COMPLETED' AND r.error_code IS NULL AND r.stop_requested_at IS NULL",
                "r.context_snapshot#>>'{agentIdentity,projectId}'=CAST(? AS text)",
                "r.completed_at > CAST(? AS timestamptz) - INTERVAL '24 hours'",
                "m.sequence_no > u.sequence_no AND m.sequence_no < ?",
                "business.task_draft", "business.article_full_preparation", "business.agent_tool_action",
                "'geo.monitor','article.generate','image.generate','video.generate'",
                "memory.user_id=? AND (memory.project_id IS NULL OR memory.project_id=?)",
                "memory.history_cutoff_at > u.created_at")
                .doesNotContain("toolCallCount"); // web_search is read-only and not a Java action receipt.
    }
    @Test void consumedOfferLookupIsOwnedAndDoesNotExcludeFailedOrUnknownResults() {
        var sql=statement("byWritingOffer");
        assertThat(sql).contains("WHERE user_id=? AND conversation_id=?",
                "snapshot#>>'{brief,authorizedContext,articleContinuation,offerMessageId}'=?")
                .doesNotContain("status IN", "status=", "status =");
    }
    @Test void latestArticleAndHistoryUseExactOwnedConversationProjectAndCurrentMessageBoundary() {
        assertThat(statement("latestArticle")).contains("p.user_id=? AND p.conversation_id=? AND a.space_id=?",
                "a.id=p.article_id AND a.user_id=p.user_id", "m.user_id=? AND m.conversation_id=?",
                "m.sequence_no < ? AND m.id <> ?", "m.created_at <= ?", "a.status <> 'ARCHIVED'",
                "ORDER BY m.sequence_no DESC,p.plan_id DESC LIMIT 1")
                .doesNotContain("article_stream_preview", "p.status='SUCCEEDED'");
        assertThat(statement("latestArticle")).contains("memory.project_id IS NULL OR memory.project_id=?",
                "memory.history_cutoff_at > p.created_at", "AS bodyPredatesMemoryBoundary");
        assertThat(statement("writingMessages")).contains("m.user_id=? AND m.conversation_id=?",
                "m.sequence_no < ? AND m.id <> ?", "m.role IN ('USER','ASSISTANT') AND m.status='COMPLETED'",
                "r.user_id=? AND r.conversation_id=?", "r.selected_space_id=?",
                "r.completed_at <= ?", "INTERVAL '24 hours'", "ORDER BY m.sequence_no DESC LIMIT 4",
                "left(m.content->>'text',800)")
                .contains("memory.user_id=? AND (memory.project_id IS NULL OR memory.project_id=?)",
                        "memory.history_cutoff_at > m.created_at")
                .doesNotContain("role='TOOL'", "conversation_summary");
    }
    private String statement(String name) {
        var config=new Configuration();config.addMapper(ArticleFullMapper.class);
        return config.getMappedStatement(ArticleFullMapper.class.getName()+"."+name).getBoundSql(Map.of())
                .getSql().replaceAll("\\s+"," ").strip();
    }
}
