package com.guanghe.backend.database;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseGateSafetyTests {

    @Test
    void acceptsOnlyExplicitIsolatedGateDatabaseNames() {
        assertThatCode(() -> DatabaseGateIntegrationTests
                .requireSafeExpectedDatabase("zhixiaohe_gate_0827_ab12"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsSharedTestProductionAndMalformedNames() {
        assertThatThrownBy(() -> DatabaseGateIntegrationTests.requireSafeExpectedDatabase("zhixiaohe"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> DatabaseGateIntegrationTests.requireSafeExpectedDatabase("zhixiaohe_test"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> DatabaseGateIntegrationTests.requireSafeExpectedDatabase("postgres"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> DatabaseGateIntegrationTests.requireSafeExpectedDatabase("zhixiaohe_gate_"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> DatabaseGateIntegrationTests.requireSafeExpectedDatabase(null))
                .isInstanceOf(IllegalStateException.class);
    }
}
