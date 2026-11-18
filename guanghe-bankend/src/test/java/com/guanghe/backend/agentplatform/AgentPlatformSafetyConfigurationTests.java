package com.guanghe.backend.agentplatform;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class AgentPlatformSafetyConfigurationTests {
    @Test void absentOrExplicitTrueSettingKeepsProductionEnforcementAvailable() {
        runner("prod").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AgentPlatformProperties.class);
        });
        runner("prod").withPropertyValues("zhixiaohe.agent-platform.enabled=TRUE").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AgentPlatformProperties.class);
        });
    }

    @Test void disablingOutsideMockFailsForDefaultDevelopmentTestAndProductionProfiles() {
        for (String[] profiles : new String[][] { {}, {"dev"}, {"test"}, {"prod"} }) {
            runner(profiles).withPropertyValues("zhixiaohe.agent-platform.enabled=false").run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasStackTraceContaining(AgentPlatformSafetyConfiguration.CONFIGURATION_ERROR);
            });
        }
    }

    @Test void mockCanDisableWithoutLoadingPlatformPropertiesOrGuard() {
        runner("mock").withPropertyValues("zhixiaohe.agent-platform.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(AgentPlatformProperties.class);
            assertThat(context).doesNotHaveBean("requireAgentPlatformEnforcement");
        });
    }

    @Test void aliasesAndMalformedValuesCannotBypassExactTrueConditionalRegistration() {
        for (String value : new String[] { "yes", "off", "0", "invalid", "" }) {
            runner("prod").withPropertyValues("zhixiaohe.agent-platform.enabled="+value).run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasStackTraceContaining(AgentPlatformSafetyConfiguration.CONFIGURATION_ERROR);
            });
        }
    }

    @Test void failureOccursBeforeOrdinarySingletonsOrExternalClientsInitialize() {
        var initialized=new AtomicBoolean();
        runner("prod").withPropertyValues("zhixiaohe.agent-platform.enabled=false")
                .withBean("externalClientFixture",Object.class,() -> { initialized.set(true); return new Object(); })
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(initialized).isFalse();
                });
    }

    private ApplicationContextRunner runner(String... profiles) {
        return new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles(profiles))
                .withUserConfiguration(AgentPlatformSafetyConfiguration.class,AgentPlatformConfiguration.class);
    }
}
