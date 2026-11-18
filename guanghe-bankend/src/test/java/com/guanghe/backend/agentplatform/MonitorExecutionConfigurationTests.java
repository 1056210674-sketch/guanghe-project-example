package com.guanghe.backend.agentplatform;

import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.task.TaskControlService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import static org.assertj.core.api.Assertions.assertThat;

class MonitorExecutionConfigurationTests {
    @Test void mockWithPaidTasksButPlatformOffDoesNotConstructMonitorComponents() {
        new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("mock"))
                .withUserConfiguration(AgentPlatformSafetyConfiguration.class, AgentPlatformConfiguration.class,
                        MonitorExecutionService.class, MonitorExecutionController.class, MonitorAdmissionScheduler.class)
                .withPropertyValues("zhixiaohe.paid-task.enabled=true", "zhixiaohe.agent-platform.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(MonitorExecutionService.class);
                    assertThat(context).doesNotHaveBean(MonitorExecutionController.class);
                    assertThat(context).doesNotHaveBean(MonitorAdmissionScheduler.class);
                });
    }

    @Test void paidTaskOffDoesNotConstructMonitorComponentsAndAutomaticExecutionHasNoOpenDefault() {
        new ApplicationContextRunner()
                .withUserConfiguration(AgentPlatformConfiguration.class, MonitorExecutionService.class,
                        MonitorExecutionController.class, MonitorAdmissionScheduler.class)
                .withPropertyValues("zhixiaohe.paid-task.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(MonitorExecutionService.class);
                    assertThat(context).doesNotHaveBean(MonitorExecutionController.class);
                    assertThat(context).doesNotHaveBean(MonitorAdmissionScheduler.class);
                    var properties=context.getBean(AgentPlatformProperties.class);
                    assertThat(properties.isAutomaticMonitorEnabled()).isFalse();
                    assertThat(properties.getMonitorBudgetEarliestTrustedDate()).isNull();
                });
    }

    @Test void expectedMonitorIdentityRefusalMustNotMarkTheCallingTransactionRollbackOnly() throws Exception {
        var attributes=new AnnotationTransactionAttributeSource();
        assertThat(attributes.getTransactionAttribute(AgentPlatformService.class.getMethod(
                "assertMonitorExecutionAllowed",long.class,long.class),AgentPlatformService.class)).isNull();
        assertThat(attributes.getTransactionAttribute(MonitorExecutionService.class.getMethod(
                "preview",AuthenticatedAccount.class,long.class,MonitorExecutionDtos.MonitorRangeInput.class),
                MonitorExecutionService.class)).isNotNull();
        assertThat(attributes.getTransactionAttribute(TaskControlService.class.getMethod(
                "admitNextMonitorAction"),TaskControlService.class)).isNotNull();
    }
}
