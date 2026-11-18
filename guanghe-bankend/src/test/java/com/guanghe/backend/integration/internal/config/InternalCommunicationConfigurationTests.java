package com.guanghe.backend.integration.internal.config;

import com.guanghe.backend.integration.internal.security.InternalNonceStore;
import com.guanghe.backend.integration.internal.security.RedisInternalNonceStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class InternalCommunicationConfigurationTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(InternalCommunicationConfiguration.class)
            .withPropertyValues(
                    "zhixiaohe.internal.enabled=true",
                    "zhixiaohe.internal.key-id=agent-service-v1",
                    "zhixiaohe.internal.secret=0123456789abcdef0123456789abcdef"
            )
            .withBean(InternalNonceStore.class, () -> (keyId, nonce, ttl) -> true);

    @Test
    void nonceTtlBelow120SecondsStopsStartup() {
        contextRunner.withPropertyValues("zhixiaohe.internal.nonce-ttl=119s")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("INTERNAL_HMAC_NONCE_TTL must be at least 120 seconds");
                });
    }

    @Test
    void defaultSecurityWindowAndNonceTtlAreAccepted() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            InternalCommunicationProperties properties = context.getBean(InternalCommunicationProperties.class);
            assertThat(properties.getAllowedClockSkew()).hasSeconds(60);
            assertThat(properties.getNonceTtl()).hasSeconds(120);
        });
    }

    @Test
    void realRedisNonceStoreIsRegisteredWhenRedisTemplateExists() {
        new ApplicationContextRunner()
                .withUserConfiguration(InternalCommunicationConfiguration.class)
                .withPropertyValues(
                        "zhixiaohe.internal.enabled=true",
                        "zhixiaohe.internal.key-id=agent-service-v1",
                        "zhixiaohe.internal.secret=0123456789abcdef0123456789abcdef"
                )
                .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(InternalNonceStore.class);
                    assertThat(context.getBean(InternalNonceStore.class))
                            .isInstanceOf(RedisInternalNonceStore.class);
                });
    }
}
