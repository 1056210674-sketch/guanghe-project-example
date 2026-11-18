package com.guanghe.backend.adminplatform;

import java.util.HashMap;
import java.util.Map;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AdminPlatformMapperTests {
    @Test void metadataQueriesParseAndBindFiltersWithoutPrivateTextProjectionOrInterpolation() {
        var configuration = new Configuration();
        configuration.addMapper(AdminPlatformMapper.class);
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("userId", 41L); parameters.put("projectId", 51L); parameters.put("status", "ACTIVE");
        parameters.put("offset", 20L); parameters.put("size", 20); parameters.put("id", 91L);
        for (var name : java.util.List.of("plans", "planCount", "documents", "documentCount", "preferences", "preferenceCount", "metadataSummary", "slots", "slotCount")) {
            var sql = configuration.getMappedStatement(AdminPlatformMapper.class.getName() + "." + name).getBoundSql(parameters).getSql();
            assertThat(sql).doesNotContain("#{", "${", "source_url", "object_key", "input_snapshot", "source_message_id", "p.content", "d.name");
            assertThat(sql).contains("?");
            assertThat(sql).doesNotContain("?AND", "?OR", "?ORDER", "?LIMIT");
        }
        parameters.put("userId", null); parameters.put("projectId", null); parameters.put("status", null);
        assertThat(configuration.getMappedStatement(AdminPlatformMapper.class.getName() + ".metadataSummary").getBoundSql(parameters).getParameterMappings()).isEmpty();
    }
}
