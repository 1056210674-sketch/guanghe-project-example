package com.guanghe.backend.database;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FormalTestDatabaseSafetyTests {

    @Test
    void acceptsOnlyTheDedicatedFormalTestDatabaseAndRoles() {
        assertThatCode(() -> FormalTestDatabaseInitializationIntegrationTests.requireExactFormalTarget(
                "zhixiaohe_test", "zhixiaohe_test_flyway", "zhixiaohe_test_app"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsSharedProductionGateOrRoleReuseTargets() {
        assertThatThrownBy(() -> FormalTestDatabaseInitializationIntegrationTests.requireExactFormalTarget(
                "zhixiaohe", "zhixiaohe_test_flyway", "zhixiaohe_test_app"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> FormalTestDatabaseInitializationIntegrationTests.requireExactFormalTarget(
                "zhixiaohe_gate_x", "zhixiaohe_test_flyway", "zhixiaohe_test_app"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> FormalTestDatabaseInitializationIntegrationTests.requireExactFormalTarget(
                "zhixiaohe_test", "zhixiaohe_test_app", "zhixiaohe_test_app"))
                .isInstanceOf(IllegalStateException.class);
    }
}
