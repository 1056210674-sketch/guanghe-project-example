package com.guanghe.backend.database;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class TestPointAccountInitializationSafetyTests {

    @Test
    void pointInitializerIsTestOnlyIdempotentAndTargetsOnlyTheFixedUser() throws Exception {
        ClassPathResource resource = new ClassPathResource("db/testdata/test001_point_account.sql");
        String script = resource.getContentAsString(StandardCharsets.UTF_8);

        assertThat(resource.exists()).isTrue();
        assertThat(script)
                .contains("current_database() <> 'zhixiaohe_test'")
                .contains("username_normalized = 'test001'")
                .contains("role = 'USER'")
                .contains("status = 'ACTIVE'")
                .contains("ON CONFLICT (user_id) DO NOTHING")
                .doesNotContain("Guanghe888", "role = 'ADMIN'", "password_hash");
        assertThat(new ClassPathResource("db/migration/test001_point_account.sql").exists()).isFalse();
    }
}
