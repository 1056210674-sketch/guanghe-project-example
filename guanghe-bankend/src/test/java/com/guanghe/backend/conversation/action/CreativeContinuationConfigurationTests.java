package com.guanghe.backend.conversation.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanghe.backend.conversation.ConversationMapper;
import com.guanghe.backend.asset.AssetMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CreativeContinuationConfigurationTests {
    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true"})
    void disabledNativeActionsStartWithoutDatabaseMappers(boolean paidTasks,boolean agentPlatform) {
        new ApplicationContextRunner().withUserConfiguration(CreativeContinuationEvidence.class)
                .withPropertyValues("zhixiaohe.paid-task.enabled="+paidTasks,
                        "zhixiaohe.agent-platform.enabled="+agentPlatform)
                .run(context->assertThat(context).hasNotFailed().doesNotHaveBean(CreativeContinuationEvidence.class));
    }

    @Test void enabledNativeActionsLoadTheirSharedEvidenceService() {
        new ApplicationContextRunner().withUserConfiguration(CreativeContinuationEvidence.class)
                .withPropertyValues("zhixiaohe.paid-task.enabled=true", "zhixiaohe.agent-platform.enabled=true")
                .withBean(ConversationMapper.class,()->mock(ConversationMapper.class))
                .withBean(AssetMapper.class,()->mock(AssetMapper.class))
                .withBean(ObjectMapper.class,ObjectMapper::new)
                .run(context->assertThat(context).hasNotFailed().hasSingleBean(CreativeContinuationEvidence.class));
    }
}
