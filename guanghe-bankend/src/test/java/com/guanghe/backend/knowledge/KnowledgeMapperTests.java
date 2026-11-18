package com.guanghe.backend.knowledge;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeMapperTests {
    @Test
    void emptySearchTermsUseStableRecencyOrderWithoutInvalidPostgresOrdinal() {
        var bound = contextDocuments(List.of());

        assertThat(sql(bound)).endsWith("ORDER BY d.updated_at DESC,d.id DESC LIMIT ?")
                .doesNotContain("ORDER BY (0", "CASE WHEN", "position(");
        assertScopedReadyDocuments(bound);
        assertThat(bound.getParameterMappings()).extracting(ParameterMapping::getProperty)
                .containsExactly("userId", "projectId", "size");
    }

    @Test
    void absentTermsAlsoUseRecencyWithoutLosingOwnershipFilters() {
        var bound = contextDocuments(null);

        assertThat(sql(bound)).endsWith("ORDER BY d.updated_at DESC,d.id DESC LIMIT ?")
                .doesNotContain("ORDER BY (0", "CASE WHEN", "position(");
        assertScopedReadyDocuments(bound);
    }

    @Test
    void populatedTermsKeepNameAndParagraphWeightingThenStableRecency() {
        var bound = contextDocuments(List.of("geo", "咖啡"));

        assertThat(sql(bound)).contains("ORDER BY (0", "THEN 3 ELSE 0 END", "THEN 1 ELSE 0 END")
                .endsWith(") DESC, d.updated_at DESC,d.id DESC LIMIT ?")
                .contains("position(? in lower(d.name)) > 0", "position(? in lower(v.paragraphs::text)) > 0");
        assertScopedReadyDocuments(bound);
        assertThat(bound.getParameterMappings()).extracting(ParameterMapping::getProperty)
                .containsExactly("userId", "projectId", "__frch_term_0", "__frch_term_0",
                        "__frch_term_1", "__frch_term_1", "size");
        assertThat(bound.getAdditionalParameter("__frch_term_0")).isEqualTo("geo");
        assertThat(bound.getAdditionalParameter("__frch_term_1")).isEqualTo("咖啡");
    }

    @Test
    void searchTextIsBoundAsDataAndCannotChangeOwnerOrProjectScope() {
        String hostileTerm = "') OR d.user_id<>41 --";
        var bound = contextDocuments(List.of(hostileTerm));

        assertThat(sql(bound)).doesNotContain(hostileTerm, "${", "#{");
        assertThat(bound.getAdditionalParameter("__frch_term_0")).isEqualTo(hostileTerm);
        assertScopedReadyDocuments(bound);
        var parameters = (Map<?, ?>) bound.getParameterObject();
        assertThat(parameters.get("userId")).isEqualTo(41L);
        assertThat(parameters.get("projectId")).isEqualTo(51L);
        assertThat(parameters.get("size")).isEqualTo(12);
    }

    private BoundSql contextDocuments(List<String> terms) {
        var configuration = new Configuration();
        configuration.addMapper(KnowledgeMapper.class);
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("userId", 41L);
        parameters.put("projectId", 51L);
        parameters.put("terms", terms);
        parameters.put("size", 12);
        return configuration.getMappedStatement(KnowledgeMapper.class.getName() + ".contextDocuments")
                .getBoundSql(parameters);
    }

    private void assertScopedReadyDocuments(BoundSql bound) {
        assertThat(sql(bound)).contains("WHERE d.user_id=? AND d.project_id=?",
                "AND d.status='READY' AND d.deleted_at IS NULL AND NOT d.conversation_memory_excluded",
                "ON v.document_id=d.id AND v.version=d.current_version");
    }

    private String sql(BoundSql bound) {
        return bound.getSql().replaceAll("\\s+", " ").trim();
    }
}
