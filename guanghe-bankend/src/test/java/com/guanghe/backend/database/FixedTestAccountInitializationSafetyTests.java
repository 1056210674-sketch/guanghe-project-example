package com.guanghe.backend.database;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FixedTestAccountInitializationSafetyTests {

    @Test
    void acceptsOnlyTheFormalTestDatabaseAndMigrationRole() {
        assertThatCode(() -> FixedTestAccountInitializationIntegrationTests.requireExactTestTarget(
                "zhixiaohe_test", "zhixiaohe_test_flyway"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsProductionOtherDatabasesAndTheRuntimeRole() {
        assertThatThrownBy(() -> FixedTestAccountInitializationIntegrationTests.requireExactTestTarget(
                "zhixiaohe", "zhixiaohe_test_flyway"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> FixedTestAccountInitializationIntegrationTests.requireExactTestTarget(
                "postgres", "zhixiaohe_test_flyway"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> FixedTestAccountInitializationIntegrationTests.requireExactTestTarget(
                "zhixiaohe_test", "zhixiaohe_test_app"))
                .isInstanceOf(IllegalStateException.class);
    }
}
